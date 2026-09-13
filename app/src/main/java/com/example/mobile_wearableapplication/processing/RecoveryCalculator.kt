package com.example.mobile_wearableapplication.processing

/** Raw HR endpoint medians; intervals are half-open and use watch nanoseconds. */
class RecoveryCalculator {
    private var recoveryStart: Long? = null
    private var finalResult: MetricResult<RecoveryRate>? = null
    private val samples = mutableListOf<HeartRateInput>()
    private data class MotionObservation(val time: Long, val moving: Boolean?)
    private val motion = mutableListOf<MotionObservation>()
    private val brokenMotion = mutableSetOf<Long>()
    fun markMotionUnavailable() { motion.lastOrNull()?.let { brokenMotion.add(it.time) } }
    private val interruptedAfter = mutableSetOf<Long>()
    fun accept(sample: HeartRateInput) {
        if (sample.sequence <= 0 || sample.timestampNanos < 0 || samples.lastOrNull()?.let {
                sample.sequence <= it.sequence || sample.timestampNanos <= it.timestampNanos } == true) return
        if (finalResult != null || recoveryStart?.let { sample.timestampNanos >= it + 60_000_000_000L } == true) return
        samples.add(sample)
        val retainFrom = recoveryStart?.minus(5_000_000_000L) ?: (sample.timestampNanos - 65_000_000_000L)
        while (samples.size > 600 || (samples.size > 1 && samples.first().timestampNanos < retainFrom)) samples.removeAt(0)
        val oldest = samples.firstOrNull()?.sequence ?: sample.sequence
        interruptedAfter.removeAll { it < oldest }
    }
    fun observe(time: Long, moving: Boolean?) {
        if (motion.lastOrNull()?.let { time <= it.time } == true) return
        if (finalResult != null || recoveryStart?.let { time > it + 60_000_000_000L } == true) return
        motion.add(MotionObservation(time, moving))
        val retainFrom = recoveryStart ?: (time - 65_000_000_000L)
        while (motion.size > 2000 || (motion.size > 2 && motion[1].time < retainFrom)) motion.removeAt(0)
        val oldest = motion.firstOrNull()?.time ?: time
        brokenMotion.removeAll { it < oldest }
    }
    fun interrupt() { samples.lastOrNull()?.let { interruptedAfter.add(it.sequence) } }
    fun moved(t0: Long?, now: Long? = null): Boolean? {
        if (t0 == null) return null
        val end = minOf(t0 + 60_000_000_000L, now ?: (t0 + 60_000_000_000L))
        if (end <= t0) return null
        if (motion.any { it.time >= t0 && it.time < end && it.moving == true }) return true
        var covered = 0L
        for ((i, point) in motion.withIndex()) {
            if (point.time >= end) break
            if (point.moving != false || point.time in brokenMotion) continue
            val from = maxOf(t0, point.time)
            val to = minOf(end, motion.getOrNull(i+1)?.time ?: end,
                point.time + SensorPreprocessor.ACCELERATION_HOLD_NANOS)
            covered += (to-from).coerceAtLeast(0L)
        }
        return if (covered == end-t0) false else null
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
        if (t0 == null) return MetricResult.Unavailable(UnavailableReason.AWAITING_PHASE_CONFIRMATION)
        recoveryStart = t0
        val end = t0 + 60_000_000_000L
        if (ended != null && ended < end) return MetricResult.Unavailable(UnavailableReason.INSUFFICIENT_DATA)
        val quality = when (moved(t0, minOf(now ?: t0, end))) {
            true -> RecoveryQuality.LOW_QUALITY
            false -> RecoveryQuality.GOOD
            null -> RecoveryQuality.UNKNOWN
        }
        finalResult?.let { result ->
            return if (result is MetricResult.Available) result.copy(value = result.value.copy(quality = quality)) else result
        }
        if ((now ?: t0) < end) return MetricResult.Unavailable(UnavailableReason.COLLECTING_RECOVERY)
        if (exerciseStart == null || exerciseStart > t0 - 5_000_000_000L)
            return MetricResult.Unavailable(UnavailableReason.INSUFFICIENT_DATA)
        val (h0, first) = endpoint(t0 - 5_000_000_000L, t0)
        val (h60, last) = endpoint(t0 + 55_000_000_000L, end)
        if (h0 == null || h60 == null || first.coverageFraction!! < 0.8 || last.coverageFraction!! < 0.8)
            return MetricResult.Unavailable(UnavailableReason.INSUFFICIENT_DATA)
        val drop = h0 - h60
        val result = MetricResult.Available(RecoveryRate(h0, h60, drop, drop, first, last, quality),
            CalculationEvidence(CalculationWindow(t0-5_000_000_000L,end), first.sampleCount+last.sampleCount,
                sources = first.sources+last.sources))
        finalResult = result
        return result
    }
}
