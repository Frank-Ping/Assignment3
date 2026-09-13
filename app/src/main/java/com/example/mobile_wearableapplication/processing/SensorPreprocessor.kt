package com.example.mobile_wearableapplication.processing

import kotlin.math.abs

data class PreprocessingStats(
    val validSamples: Long = 0, val invalidSamples: Long = 0,
    val duplicateOrLateSamples: Long = 0, val longGaps: Long = 0,
    val rapidChanges: Long = 0, val lastIssue: String? = null
)

data class CoveredWindow(
    val window: CalculationWindow,
    val coveredSeconds: Double,
    val coverageFraction: Double,
    val sampleCount: Int,
    val mean: Double?,
    val smoothingCoveredSeconds: Double
)

data class PreprocessingSnapshot(
    val asOfWatchNanos: Long? = null,
    val rawHeartRateBpm: Double? = null,
    val heartRate3s: CoveredWindow? = null,
    val heartRate5s: CoveredWindow? = null,
    val acceleration1s: CoveredWindow? = null,
    val heartRateStats: PreprocessingStats = PreprocessingStats(),
    val accelerationStats: PreprocessingStats = PreprocessingStats()
)

/** Pure Kotlin; bounded windows and cumulative diagnostics, no Android/receipt clock. */
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
        var stats = PreprocessingStats()
        var lastSequence = 0L
        var segment = 0L

        fun add(sequence: Long, time: Long, value: Double?, source: SampleSource, valid: Boolean): Boolean {
            if (sequence <= 0 || time < 0) {
                stats = stats.copy(invalidSamples = stats.invalidSamples + 1, lastIssue = "Invalid sequence/time")
                return false
            }
            val previous = points.lastOrNull()
            if (sequence <= lastSequence || (previous != null && time <= previous.time)) {
                stats = stats.copy(duplicateOrLateSamples = stats.duplicateOrLateSamples + 1,
                    lastIssue = "Duplicate/late sample excluded from live processing")
                return false
            }
            lastSequence = sequence
            val gap = previous != null && time - previous.time > hold
            if (gap) {
                segment++
                stats = stats.copy(longGaps = stats.longGaps + 1, lastIssue = "Gap exceeds maximum hold")
            }
            if (previous != null && previous.source != source) segment++
            if (!valid) {
                segment++
                stats = stats.copy(invalidSamples = stats.invalidSamples + 1, lastIssue = "Non-finite XYZ or invalid heart rate")
            } else {
                stats = stats.copy(validSamples = stats.validSamples + 1)
                // Engineering flag only; retain fast changes rather than diagnosing/removing them.
                if (!gap && previous?.valid == true && previous.mayHold && previous.source == source &&
                    previous.value != null && value != null && abs(value - previous.value) > 30.0) {
                    stats = stats.copy(rapidChanges = stats.rapidChanges + 1, lastIssue = "HR change >30 bpm; retained for review")
                }
            }
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
            var count = 0
            val latest = points.lastOrNull()
            for ((index, point) in points.withIndex()) {
                // Never hold a previous phase's reading across its boundary.
                if (point.time < notBefore) continue
                if (point.valid && point.time >= start && point.time < end) count++
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
            return CoveredWindow(CalculationWindow(start, end), covered.toDouble() / SECOND,
                if (end > start) covered.toDouble() / (end - start) else 0.0, count, mean,
                smoothDuration.toDouble() / SECOND)
        }
    }

    private val heartRate = Stream(HEART_RATE_HOLD_NANOS, 256)
    private val acceleration = Stream(ACCELERATION_HOLD_NANOS, 1500)
    private var asOf: Long? = null

    fun accept(sample: HeartRateInput) {
        if (heartRate.add(sample.sequence, sample.timestampNanos, sample.bpm, sample.source,
                sample.bpm.isFinite() && sample.bpm > 0)) advance(sample.timestampNanos)
    }
    fun accept(sample: AccelerationInput) {
        if (acceleration.add(sample.sequence, sample.timestampNanos, null, sample.source,
                sample.x.isFinite() && sample.y.isFinite() && sample.z.isFinite())) advance(sample.timestampNanos)
    }
    private fun advance(time: Long) { asOf = maxOf(asOf ?: time, time) }
    fun breakContinuity() { heartRate.breakContinuity(); acceleration.breakContinuity() }

    fun snapshot(phaseStartedAt: Long? = null): PreprocessingSnapshot {
        val end = asOf?.let { maxOf(it, phaseStartedAt ?: it) }
        val boundary = phaseStartedAt ?: 0L
        return PreprocessingSnapshot(end, heartRate.points.lastOrNull()?.takeIf { it.valid && it.time >= boundary }?.value,
            end?.let { heartRate.window(it, 3 * SECOND, boundary) }, end?.let { heartRate.window(it, 5 * SECOND, boundary) },
            end?.let { acceleration.window(it, SECOND) }, heartRate.stats, acceleration.stats)
    }
}
