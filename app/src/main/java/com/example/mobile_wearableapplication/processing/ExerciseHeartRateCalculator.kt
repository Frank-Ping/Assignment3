package com.example.mobile_wearableapplication.processing

/** Fold completed intervals into session totals; replay only a bounded recent tail. */
class ExerciseHeartRateCalculator {
    private val samples = mutableListOf<HeartRateInput>()
    private val seed = mutableListOf<HeartRateInput>()
    private val interruptedAfter = mutableSetOf<Long>()
    private var closedAt: Long? = null
    private var closedResult: MetricResult<ExerciseHeartRate>? = null
    private var currentUnavailable = false
    private var committedEnd = -1L
    private var configuredStart: Long? = null
    private var lostBefore = -1L
    private var lastSequence = 0L
    private var lastTime = -1L
    private val totals = Totals()
    private val committedSmooth = SensorPreprocessor()
    private class Totals(var weighted: Double = 0.0, var covered: Long = 0,
        var count: Long = 0, var peak: Double? = null, val sources: MutableSet<SampleSource> = mutableSetOf()) {
        fun copy() = Totals(weighted, covered, count, peak, sources.toMutableSet())
    }
    fun markUnavailable() { currentUnavailable = true }
    fun accept(sample: HeartRateInput) {
        if (sample.sequence <= lastSequence || sample.timestampNanos <= lastTime) return
        lastSequence = sample.sequence; lastTime = sample.timestampNanos
        if (closedAt?.let { sample.timestampNanos >= it } == true) return
        currentUnavailable = false
        samples.add(sample)
        // Before phase confirmation only a recent tail can be retained.
        while (samples.size > 600 || (configuredStart == null && samples.size > 2 &&
                samples[1].timestampNanos < lastTime - 65_000_000_000L)) {
            lostBefore = samples.removeAt(0).timestampNanos
        }
    }
    fun interrupt() { samples.lastOrNull()?.let { interruptedAfter.add(it.sequence) } }
    private fun add(point: HeartRateInput, end: Long, smooth: SensorPreprocessor, total: Totals, start: Long) {
        smooth.accept(point)
        fun peak(at: Long) {
            smooth.snapshot(start, at).heartRate3s?.mean?.let { total.peak = maxOf(total.peak ?: it, it) }
        }
        peak(point.timestampNanos)
        if (point.sequence in interruptedAfter) smooth.breakContinuity()
        if (!point.bpm.isFinite() || point.bpm <= 0) return
        total.count++; total.sources.add(point.source)
        if (point.sequence in interruptedAfter) return
        val dt = (minOf(end, point.timestampNanos + SensorPreprocessor.HEART_RATE_HOLD_NANOS) - point.timestampNanos).coerceAtLeast(0L)
        total.covered += dt; total.weighted += point.bpm * (dt / 1e9)
        peak(point.timestampNanos + dt)
    }
    fun result(start: Long?, finish: Long?, now: Long?): MetricResult<ExerciseHeartRate> {
        if (start == null) return MetricResult.Unavailable(UnavailableReason.AWAITING_PHASE_CONFIRMATION)
        if (finish != null && closedAt == finish) closedResult?.let { return it }
        val end = finish ?: now ?: start
        if (lostBefore >= start || end < committedEnd || (configuredStart != null && configuredStart != start))
            return MetricResult.Unavailable(UnavailableReason.INSUFFICIENT_DATA)
        configuredStart = start
        while (samples.isNotEmpty() && samples.first().timestampNanos < start) samples.removeAt(0)
        // A 65s tail permits delayed phase boundaries without retaining the full workout.
        while (samples.size > 1 && samples[1].timestampNanos < end - 65_000_000_000L) {
            val point = samples.removeAt(0)
            val next = samples.first().timestampNanos
            add(point, next, committedSmooth, totals, start)
            committedEnd = next
            seed.add(point)
            while (seed.size > 1 && seed.first().timestampNanos < next - 6_000_000_000L) seed.removeAt(0)
            val oldest = seed.firstOrNull()?.sequence ?: point.sequence
            interruptedAfter.removeAll { it < oldest }
        }
        val smooth = SensorPreprocessor()
        seed.forEach { smooth.accept(it); if (it.sequence in interruptedAfter) smooth.breakContinuity() }
        val total = totals.copy()
        val points = samples.filter { it.timestampNanos < end }
        points.forEachIndexed { i, point -> add(point, points.getOrNull(i+1)?.timestampNanos ?: end, smooth, total, start) }
        val current = smooth.snapshot(start, end).heartRate3s?.mean
        if (current != null) total.peak = maxOf(total.peak ?: current, current)
        if (total.covered == 0L) return MetricResult.Unavailable(UnavailableReason.INSUFFICIENT_DATA)
        val result = MetricResult.Available(ExerciseHeartRate(if (finish == null && !currentUnavailable) current else null,
            total.weighted / (total.covered / 1e9), total.peak), CalculationEvidence(CalculationWindow(start,end),
            total.count, if (end > start) total.covered.toDouble()/(end-start) else 0.0,total.sources.toSet()))
        if (finish != null) { closedAt = finish; closedResult = result }
        return result
    }
}
