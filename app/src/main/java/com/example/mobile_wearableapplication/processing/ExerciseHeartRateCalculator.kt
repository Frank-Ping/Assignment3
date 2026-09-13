package com.example.mobile_wearableapplication.processing

/** Retains this session's HR so delayed phase confirmations can be applied by measurement time. */
class ExerciseHeartRateCalculator {
    private var currentUnavailable = false
    fun markUnavailable() { currentUnavailable = true }
    private val samples = mutableListOf<HeartRateInput>()
    private val interruptedAfter = mutableSetOf<Long>()
    fun accept(sample: HeartRateInput) {
        if (sample.sequence <= 0 || sample.timestampNanos < 0) return
        if (samples.lastOrNull()?.let { sample.sequence <= it.sequence || sample.timestampNanos <= it.timestampNanos } == true) return
        currentUnavailable = false
        samples.add(sample)
    }
    fun interrupt() { samples.lastOrNull()?.let { interruptedAfter.add(it.sequence) } }

    fun result(start: Long?, finish: Long?, now: Long?): MetricResult<ExerciseHeartRate> {
        if (start == null) return MetricResult.Unavailable(UnavailableReason.AWAITING_PHASE_CONFIRMATION)
        val end = finish ?: now ?: start
        val points = samples.filter { it.timestampNanos >= start && it.timestampNanos < end }
        val smooth = SensorPreprocessor()
        var weighted = 0.0
        var covered = 0L
        var peak: Double? = null
        fun recordPeak(value: Double?) { if (value != null) peak = maxOf(peak ?: value, value) }
        for ((index, point) in points.withIndex()) {
            smooth.accept(point)
            recordPeak(smooth.snapshot(start).heartRate3s?.mean)
            if (point.sequence in interruptedAfter) smooth.breakContinuity()
            if (!point.bpm.isFinite() || point.bpm <= 0 || point.sequence in interruptedAfter) continue
            val next = points.getOrNull(index + 1)?.timestampNanos ?: end
            val dt = (minOf(end, next, point.timestampNanos + SensorPreprocessor.HEART_RATE_HOLD_NANOS) - point.timestampNanos).coerceAtLeast(0L)
            recordPeak(smooth.snapshot(start, point.timestampNanos + dt).heartRate3s?.mean)
            covered += dt
            weighted += point.bpm * (dt / 1e9)
        }
        val current = smooth.snapshot(start, end).heartRate3s?.mean
        recordPeak(current)
        if (covered == 0L) return MetricResult.Unavailable(UnavailableReason.INSUFFICIENT_DATA)
        val valid = points.filter { it.bpm.isFinite() && it.bpm > 0 }
        return MetricResult.Available(ExerciseHeartRate(if (finish == null && !currentUnavailable) current else null,
            weighted / (covered / 1e9), peak), CalculationEvidence(CalculationWindow(start, end),
            valid.size.toLong(), if (end > start) covered.toDouble() / (end - start) else 0.0,
            valid.map { it.source }.toSet()))
    }
}
