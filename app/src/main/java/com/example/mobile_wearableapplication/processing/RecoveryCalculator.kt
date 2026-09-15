package com.example.mobile_wearableapplication.processing

/** Raw HR endpoint medians; intervals are half-open and use watch nanoseconds. */
class RecoveryCalculator {
    private var recoveryStart: Long? = null
    private var finalResult: MetricResult<RecoveryRate>? = null
    private val samples = mutableListOf<HeartRateInput>()
    private val interruptedAfter = mutableSetOf<Long>()
    private data class MotionObservation(val time: Long, val state: MotionState)
    private val motion = mutableListOf<MotionObservation>()
    private var invalidated = false
    fun observe(time: Long, state: MotionState) {
        if (motion.lastOrNull()?.let { time <= it.time } == true) return
        motion.add(MotionObservation(time, state))
        while (motion.size > 2 && motion[1].time < time - 70_000_000_000L) motion.removeAt(0)
        while (motion.size > 2500) motion.removeAt(0)
    }
    fun accept(sample: HeartRateInput) {
        if (sample.sequence <= 0 || sample.timestampNanos < 0 || samples.lastOrNull()?.let {
                sample.sequence <= it.sequence || sample.timestampNanos <= it.timestampNanos } == true) return
        samples.add(sample)
        val retainFrom = sample.timestampNanos - 70_000_000_000L
        while (samples.size > 600 || (samples.size > 1 && samples.first().timestampNanos < retainFrom)) samples.removeAt(0)
        val oldest = samples.firstOrNull()?.sequence ?: sample.sequence
        interruptedAfter.removeAll { it < oldest }
    }
    fun interrupt() {
        samples.lastOrNull()?.let { interruptedAfter.add(it.sequence) }
        if (recoveryStart != null && finalResult == null) invalidated = true
    }
    fun remaining(t0: Long?, now: Long?, ended: Long?): Long? {
        if (t0 == null || ended != null) return null
        return ((t0 + 60_000_000_000L - (now ?: t0)).coerceAtLeast(0L) + 999_999_999L) / 1_000_000_000L
    }
    private fun endpoint(start: Long, end: Long): Pair<Double?, CalculationEvidence> {
        val points = samples.filter { it.timestampNanos >= start && it.timestampNanos < end }
        val valid = points.filter { it.bpm.isFinite() && it.bpm > 0 }
        var coverage = 0L
        for ((i, point) in points.withIndex()) {
            if (!point.bpm.isFinite() || point.bpm <= 0 || point.sequence in interruptedAfter) continue
            coverage += (minOf(end, points.getOrNull(i + 1)?.timestampNanos ?: end,
                point.timestampNanos + SensorPreprocessor.HEART_RATE_HOLD_NANOS) - point.timestampNanos).coerceAtLeast(0L)
        }
        val values = valid.map { it.bpm }.sorted()
        val mid = values.size / 2
        val median = if (values.isEmpty()) null else if (values.size % 2 == 0) values[mid-1]/2 + values[mid]/2 else values[mid]
        return median to CalculationEvidence(CalculationWindow(start, end), valid.size.toLong(),
            coverage.toDouble() / (end-start), valid.map { it.source }.toSet())
    }
    fun result(t0: Long?, exerciseStart: Long?, now: Long?, ended: Long?): MetricResult<RecoveryRate> {
        if (recoveryStart != t0) {
            recoveryStart = t0
            finalResult = null
            invalidated = false
        }
        if (t0 == null) return MetricResult.Unavailable(UnavailableReason.AWAITING_PHASE_CONFIRMATION)
        val end = t0 + 60_000_000_000L
        if (ended != null && ended < end) return MetricResult.Unavailable(UnavailableReason.INSUFFICIENT_DATA)
        finalResult?.let { return it }
        // Invalidate immediately on observed motion, before a delayed phase ACK can arrive.
        if (motion.any { it.time >= t0 && it.time < end && it.state != MotionState.STILL }) invalidated = true
        if (invalidated) return MetricResult.Unavailable(UnavailableReason.RECOVERY_INTERRUPTED)
        if ((now ?: t0) < end) return MetricResult.Unavailable(UnavailableReason.COLLECTING_RECOVERY)
        // Both streams must reach the window end before a result can be persisted.
        if ((motion.lastOrNull()?.time ?: -1L) < end || (samples.lastOrNull()?.timestampNanos ?: -1L) < end)
            return MetricResult.Unavailable(if (ended == null) UnavailableReason.COLLECTING_RECOVERY else UnavailableReason.INSUFFICIENT_DATA)
        val observations = motion.filter { it.time >= t0 - SensorPreprocessor.ACCELERATION_HOLD_NANOS && it.time <= end }
        if (observations.isEmpty() || observations.first().time > t0 + SensorPreprocessor.ACCELERATION_HOLD_NANOS ||
            end - observations.last().time > SensorPreprocessor.ACCELERATION_HOLD_NANOS ||
            observations.zipWithNext().any { (a, b) -> b.time - a.time > SensorPreprocessor.ACCELERATION_HOLD_NANOS })
            return MetricResult.Unavailable(UnavailableReason.INSUFFICIENT_DATA)
        val recoverySamples = samples.filter { it.timestampNanos >= t0 && it.timestampNanos <= end }
        if (recoverySamples.isEmpty() || recoverySamples.first().timestampNanos - t0 > SensorPreprocessor.HEART_RATE_HOLD_NANOS ||
            end - recoverySamples.last().timestampNanos > SensorPreprocessor.HEART_RATE_HOLD_NANOS ||
            recoverySamples.any { !it.bpm.isFinite() || it.bpm <= 0 } ||
            recoverySamples.zipWithNext().any { (a, b) -> b.timestampNanos - a.timestampNanos > SensorPreprocessor.HEART_RATE_HOLD_NANOS })
            return MetricResult.Unavailable(UnavailableReason.INSUFFICIENT_DATA)
        if (exerciseStart == null || exerciseStart > t0 - 5_000_000_000L)
            return MetricResult.Unavailable(UnavailableReason.INSUFFICIENT_DATA)
        val (h0, first) = endpoint(t0 - 5_000_000_000L, t0)
        val (h60, last) = endpoint(t0 + 55_000_000_000L, end)
        if (h0 == null || h60 == null || first.coverageFraction!! < 0.8 || last.coverageFraction!! < 0.8)
            return MetricResult.Unavailable(UnavailableReason.INSUFFICIENT_DATA)
        val drop = h0 - h60
        val result = MetricResult.Available(RecoveryRate(h0, h60, drop),
            CalculationEvidence(CalculationWindow(t0-5_000_000_000L,end), first.sampleCount+last.sampleCount,
                sources = first.sources+last.sources))
        finalResult = result
        return result
    }
}
