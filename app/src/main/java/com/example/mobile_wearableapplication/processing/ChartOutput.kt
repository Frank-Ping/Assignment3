package com.example.mobile_wearableapplication.processing

/** All timestamps are watch elapsed nanoseconds. Null values must render as gaps. */
data class ChartPoint(val timestampNanos: Long, val value: Double?, val source: SampleSource)
data class ChartOutput(
    val heartRate: List<ChartPoint> = emptyList(),
    val zoneIntervals: List<ZoneInterval> = emptyList()
)
data class SessionSummary(
    val endedAtNanos: Long,
    val restingHeartRate: MetricResult<Double>,
    val recovery: MetricResult<RecoveryRate>
)

/** Display history only: dropping old points never changes metric accumulators. */
class ChartBuffer {
    private val hr = ArrayDeque<ChartPoint>()
    private var lastHr: Long? = null

    fun heartRate(sample: HeartRateInput) {
        if (lastHr?.let { sample.timestampNanos <= it } == true) return
        lastHr = sample.timestampNanos
        val value = sample.bpm.takeIf { it.isFinite() && it > 0 }
        hr.addLast(ChartPoint(sample.timestampNanos, value, sample.source))
        while (hr.size > 43_201 || (hr.isNotEmpty() &&
                hr.first().timestampNanos < sample.timestampNanos - 43_200_000_000_000L)) hr.removeFirst()
    }

    fun snapshot(intervals: List<ZoneInterval>) = ChartOutput(hr.toList(), intervals)
}
