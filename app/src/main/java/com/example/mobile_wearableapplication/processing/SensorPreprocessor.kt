package com.example.mobile_wearableapplication.processing

data class CoveredWindow(
    val coverageFraction: Double,
    val mean: Double?
)

data class PreprocessingSnapshot(
    val asOfWatchNanos: Long? = null,
    val heartRate3s: CoveredWindow? = null
)

/** Pure Kotlin; bounded heart-rate smoothing, no Android/receipt clock. */
class SensorPreprocessor {
    companion object {
        const val HEART_RATE_HOLD_NANOS = 3_000_000_000L
        const val ACCELERATION_HOLD_NANOS = 200_000_000L
        private const val SECOND = 1_000_000_000L
    }

    private data class Point(
        val time: Long, val value: Double?, val valid: Boolean,
        val source: SampleSource, val segment: Long, val mayHold: Boolean = true
    )
    private class Stream(val hold: Long, val capacity: Int) {
        val points = mutableListOf<Point>()
        var lastSequence = 0L
        var segment = 0L

        fun add(sequence: Long, time: Long, value: Double?, source: SampleSource, valid: Boolean): Boolean {
            if (sequence <= 0 || time < 0) return false
            val previous = points.lastOrNull()
            if (sequence <= lastSequence || (previous != null && time <= previous.time)) {
                return false
            }
            lastSequence = sequence
            val gap = previous != null && time - previous.time > hold
            if (gap) segment++
            if (previous != null && previous.source != source) segment++
            if (!valid) segment++
            points.add(Point(time, value, valid, source, segment))
            // Keep one predecessor for interval clipping at the six-second retention boundary.
            while (points.size > 2 && points[1].time < time - 6 * SECOND) points.removeAt(0)
            while (points.size > capacity) points.removeAt(0)
            return true
        }

        fun breakContinuity() {
            if (points.lastOrNull()?.mayHold == true) {
                points[points.lastIndex] = points.last().copy(mayHold = false)
                segment++
            }
        }

        fun window(end: Long, duration: Long, notBefore: Long = 0L): CoveredWindow {
            val start = maxOf((end - duration).coerceAtLeast(0L), notBefore).coerceAtMost(end)
            var covered = 0L
            var weighted = 0.0
            var smoothDuration = 0L
            val latest = points.lastOrNull()
            for ((index, point) in points.withIndex()) {
                // Never hold a previous phase's reading across its boundary.
                if (point.time < notBefore) continue
                if (!point.valid || !point.mayHold) continue
                val nextTime = points.getOrNull(index + 1)?.time ?: end
                val from = maxOf(start, point.time)
                val to = minOf(end, nextTime, point.time + hold)
                val nanos = (to - from).coerceAtLeast(0L)
                covered += nanos
                if (point.segment == latest?.segment && point.value != null) {
                    weighted += point.value * (nanos.toDouble() / SECOND)
                    smoothDuration += nanos
                }
            }
            val fresh = latest != null && latest.valid && latest.mayHold && latest.time >= notBefore && end - latest.time <= hold
            val mean = if (fresh && smoothDuration > 0) weighted / (smoothDuration.toDouble() / SECOND) else null
            return CoveredWindow(
                if (end > start) covered.toDouble() / (end - start) else 0.0, mean)
        }
    }

    private val heartRate = Stream(HEART_RATE_HOLD_NANOS, 256)
    private var lastAccelerationSequence = 0L
    private var lastAccelerationTime = -1L
    private var asOf: Long? = null

    fun accept(sample: HeartRateInput) {
        if (heartRate.add(sample.sequence, sample.timestampNanos, sample.bpm, sample.source,
                sample.bpm.isFinite() && sample.bpm > 0)) advance(sample.timestampNanos)
    }
    fun accept(sample: AccelerationInput) {
        if (sample.sequence <= lastAccelerationSequence || sample.timestampNanos <= lastAccelerationTime) return
        lastAccelerationSequence = sample.sequence
        lastAccelerationTime = sample.timestampNanos
        // Acceleration advances the measurement clock even when its XYZ is invalid, so stale
        // heart-rate values expire. MotionDetector handles XYZ validity and motion continuity.
        advance(sample.timestampNanos)
    }
    private fun advance(time: Long) { asOf = maxOf(asOf ?: time, time) }
    fun breakContinuity() { heartRate.breakContinuity() }

    fun snapshot(phaseStartedAt: Long? = null, evaluationTime: Long? = null): PreprocessingSnapshot {
        val end = asOf?.let { maxOf(it, phaseStartedAt ?: it, evaluationTime ?: it) }
        val boundary = phaseStartedAt ?: 0L
        return PreprocessingSnapshot(end, end?.let { heartRate.window(it, 3 * SECOND, boundary) })
    }
}
