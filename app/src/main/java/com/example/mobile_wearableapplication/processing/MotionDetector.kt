package com.example.mobile_wearableapplication.processing

import kotlin.math.abs
import kotlin.math.sqrt

enum class MotionState { UNKNOWN, STILL, MOTION }
data class MotionResult(
    val state: MotionState = MotionState.UNKNOWN,
    val rms: MetricResult<Double> = MetricResult.Unavailable(UnavailableReason.INSUFFICIENT_DATA)
) {
    val stillnessVerified: Boolean? get() = if (state == MotionState.UNKNOWN) null else state == MotionState.STILL
    val motionDetected: Boolean? get() = if (state == MotionState.UNKNOWN) null else state == MotionState.MOTION
}

/** Magnitude-minus-gravity approximation; thresholds are engineering defaults, not calibrated. */
class MotionDetector(private val stillThreshold: Double = 0.3, private val motionThreshold: Double = 0.8) {
    private data class Point(val time: Long, val dynamic: Double, val source: SampleSource)
    private val points = mutableListOf<Point>()
    private var lastSequence = 0L
    private var lastTime = -1L
    private var result = MotionResult()
    init { require(stillThreshold >= 0 && motionThreshold > stillThreshold) }

    fun interrupt() {
        points.clear()
        result = MotionResult()
    }

    fun accept(sample: AccelerationInput) {
        if (sample.sequence <= lastSequence || sample.timestampNanos <= lastTime) return
        val gap = lastTime >= 0 && sample.timestampNanos - lastTime > SensorPreprocessor.ACCELERATION_HOLD_NANOS
        lastSequence = sample.sequence
        lastTime = sample.timestampNanos
        val magnitude = Math.hypot(Math.hypot(sample.x, sample.y), sample.z)
        if (!magnitude.isFinite()) { interrupt(); return }
        if (gap || points.lastOrNull()?.source?.let { it != sample.source } == true) interrupt()
        points.add(Point(sample.timestampNanos, abs(magnitude - 9.81), sample.source))
        val start = sample.timestampNanos - 1_000_000_000L
        while (points.size > 2 && points[1].time <= start) points.removeAt(0)
        while (points.size > 1500) points.removeAt(0)
        if (points.size < 6 || points.first().time > start) { result = MotionResult(); return }
        val window = points.filter { it.time >= start }
        val rms = sqrt(window.sumOf { it.dynamic * it.dynamic } / window.size)
        if (!rms.isFinite()) { interrupt(); return }
        val classification = when {
            rms <= stillThreshold -> MotionState.STILL
            rms >= motionThreshold -> MotionState.MOTION
            else -> result.state // Unknown also persists inside the dead band.
        }
        result = MotionResult(classification, MetricResult.Available(rms,
            CalculationEvidence(CalculationWindow(start, sample.timestampNanos), points.size.toLong(),
                1.0, points.map { it.source }.toSet())))
    }

    // Measurement gaps are checked in accept(); receipt timeouts call interrupt().
    // Other sensor timestamps must not invalidate a pending accelerometer batch.
    fun snapshot(): MotionResult = result
}
