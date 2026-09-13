package com.example.mobile_wearableapplication.processing

/** Engineering thresholds; candidate must persist across HR observations for three seconds. */
class IntensityClassifier(private val hrMax: Double = 200.0) {
    private var lastTime: Long? = null
    private var candidate: IntensityZone? = null
    private var candidateSince = 0L
    private var value = ExerciseIntensity(null, IntensityZone.UNCLASSIFIED, hrMaxBpm = hrMax)
    fun reset() {
        lastTime = null
        candidate = null
        value = ExerciseIntensity(null, IntensityZone.UNCLASSIFIED, hrMaxBpm = hrMax)
    }
    fun missing(): ExerciseIntensity {
        reset()
        value = value.copy(zone = IntensityZone.MISSING)
        return value
    }
    fun accept(time: Long, smoothedBpm: Double?): ExerciseIntensity {
        if (lastTime?.let { time <= it } == true) return value
        if (lastTime?.let { time - it > SensorPreprocessor.HEART_RATE_HOLD_NANOS } == true) reset()
        lastTime = time
        if (!hrMax.isFinite() || hrMax <= 0 || smoothedBpm == null || !smoothedBpm.isFinite() || smoothedBpm <= 0) {
            candidate = null
            value = ExerciseIntensity(null, IntensityZone.UNCLASSIFIED, hrMaxBpm = hrMax)
            return value
        }
        if (value.zone == IntensityZone.MISSING) value = value.copy(zone = IntensityZone.UNCLASSIFIED)
        val percentage = smoothedBpm / hrMax * 100.0
        if (!percentage.isFinite()) { reset(); return value }
        val next = when {
            percentage < 50.0 -> IntensityZone.LOW
            percentage < 70.0 -> IntensityZone.MODERATE
            else -> IntensityZone.HIGH
        }
        if (next == value.zone) candidate = null
        else {
            if (candidate != next) { candidate = next; candidateSince = time }
            if (time - candidateSince >= 3_000_000_000L) {
                value = value.copy(zone = next, confirmedAtNanos = time)
                candidate = null
            }
        }
        value = value.copy(percentage = percentage)
        return value
    }
    fun snapshot(): ExerciseIntensity = value
}
