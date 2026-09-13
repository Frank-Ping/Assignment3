package com.example.mobile_wearableapplication.processing

/** Raw HR endpoint medians; intervals are half-open and use watch nanoseconds. */
class RecoveryCalculator {
    private val samples = mutableListOf<HeartRateInput>()
    private val movement = mutableListOf<Long>()
    private val interruptedAfter = mutableSetOf<Long>()
    fun accept(sample: HeartRateInput) {
        if (sample.sequence <= 0 || sample.timestampNanos < 0 || samples.lastOrNull()?.let {
                sample.sequence <= it.sequence || sample.timestampNanos <= it.timestampNanos } == true) return
        samples.add(sample)
    }
    fun observe(time: Long, moving: Boolean) { if (moving) movement.add(time) }
    fun interrupt() { samples.lastOrNull()?.let { interruptedAfter.add(it.sequence) } }
    fun moved(t0: Long?): Boolean? = t0?.let { start -> movement.any { it >= start && it < start + 60_000_000_000L } }
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
        val end = t0 + 60_000_000_000L
        if (moved(t0) == true) return MetricResult.Unavailable(UnavailableReason.INTERRUPTED_BY_MOVEMENT)
        if (ended != null && ended < end) return MetricResult.Unavailable(UnavailableReason.INSUFFICIENT_DATA)
        if ((now ?: t0) < end) return MetricResult.Unavailable(UnavailableReason.COLLECTING_RECOVERY)
        if (exerciseStart == null || exerciseStart > t0 - 5_000_000_000L)
            return MetricResult.Unavailable(UnavailableReason.INSUFFICIENT_DATA)
        val (h0, first) = endpoint(t0 - 5_000_000_000L, t0)
        val (h60, last) = endpoint(t0 + 55_000_000_000L, end)
        if (h0 == null || h60 == null || first.coverageFraction!! < 0.8 || last.coverageFraction!! < 0.8)
            return MetricResult.Unavailable(UnavailableReason.INSUFFICIENT_DATA)
        val drop = h0 - h60
        return MetricResult.Available(RecoveryRate(h0, h60, drop, drop, first, last),
            CalculationEvidence(CalculationWindow(t0-5_000_000_000L,end), first.sampleCount+last.sampleCount,
                sources = first.sources+last.sources))
    }
}
