package com.example.mobile_wearableapplication.processing

/** Session-only baseline; watch nanoseconds, raw HR median, bounded history. */
class RestingHeartRateCalculator {
    private data class Observation(val time: Long, val still: Boolean)
    private val motion = mutableListOf<Observation>()
    private val heartRate = mutableListOf<HeartRateInput>()
    private var lastHrSequence = 0L
    private var frozen: MetricResult<Double>? = null
    private val duration = 30_000_000_000L

    fun accept(sample: HeartRateInput) {
        if (frozen != null || sample.sequence <= lastHrSequence || sample.timestampNanos < 0 ||
            heartRate.lastOrNull()?.let { sample.timestampNanos <= it.timestampNanos } == true) return
        lastHrSequence = sample.sequence
        heartRate.add(sample)
        while (heartRate.size > 2 && heartRate[1].timestampNanos < sample.timestampNanos - duration - 5_000_000_000L)
            heartRate.removeAt(0)
        while (heartRate.size > 600) heartRate.removeAt(0)
    }

    fun observe(time: Long, still: Boolean) {
        if (frozen != null || motion.lastOrNull()?.let { time <= it.time } == true) return
        motion.add(Observation(time, still))
        while (motion.size > 2 && motion[1].time < time - duration - 5_000_000_000L) motion.removeAt(0)
        while (motion.size > 1500) motion.removeAt(0)
    }

    fun interrupt() {
        if (frozen == null) { motion.clear(); heartRate.clear() }
    }

    fun freeze(start: Long?, end: Long) {
        if (frozen == null) {
            val value = result(start, end)
            frozen = if (value is MetricResult.Available) value
                else MetricResult.Unavailable(UnavailableReason.INSUFFICIENT_DATA)
        }
    }

    fun result(start: Long?, endExclusive: Long? = null): MetricResult<Double> {
        frozen?.let { return it }
        if (start == null) return MetricResult.Unavailable(UnavailableReason.AWAITING_PHASE_CONFIRMATION)
        val observations = motion.filter { it.time >= start && (endExclusive == null || it.time < endExclusive) }
        val end = observations.lastOrNull()?.time
            ?: return MetricResult.Unavailable(UnavailableReason.COLLECTING_BASELINE)
        var stillSince: Long? = null
        var previous: Long? = null
        for (point in observations) {
            if (!point.still) stillSince = null
            else if (stillSince == null || previous?.let { point.time - it > SensorPreprocessor.ACCELERATION_HOLD_NANOS } == true)
                stillSince = point.time
            previous = point.time
        }
        if (stillSince == null || end - stillSince < duration)
            return MetricResult.Unavailable(UnavailableReason.COLLECTING_BASELINE)
        val from = end - duration
        val readings = heartRate.filter { it.timestampNanos >= from && it.timestampNanos < end }
        val valid = readings.filter { it.bpm.isFinite() && it.bpm > 0 }
        var covered = 0L
        for ((index, reading) in readings.withIndex()) {
            if (!reading.bpm.isFinite() || reading.bpm <= 0) continue
            val next = readings.getOrNull(index + 1)?.timestampNanos ?: end
            covered += (minOf(end, next, reading.timestampNanos + SensorPreprocessor.HEART_RATE_HOLD_NANOS) - reading.timestampNanos).coerceAtLeast(0L)
        }
        val coverage = covered.toDouble() / duration
        if (coverage < 0.8 || valid.isEmpty()) return MetricResult.Unavailable(UnavailableReason.INSUFFICIENT_DATA)
        val values = valid.map { it.bpm }.sorted()
        val middle = values.size / 2
        val median = if (values.size % 2 == 0) values[middle - 1] / 2 + values[middle] / 2 else values[middle]
        return MetricResult.Available(median, CalculationEvidence(CalculationWindow(from, end),
            valid.size.toLong(), coverage, valid.map { it.source }.toSet()))
    }
}
