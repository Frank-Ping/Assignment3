package com.example.mobile_wearableapplication.processing

/** All timestamps are watch elapsed nanoseconds. Null values must render as gaps. */
data class ChartPoint(val timestampNanos: Long, val value: Double?, val source: SampleSource)
data class DataGap(val stream: String, val startNanos: Long, val endNanos: Long?, val reason: String)
data class ChartOutput(
    val heartRate: List<ChartPoint> = emptyList(),
    val rms: List<ChartPoint> = emptyList(),
    val gaps: List<DataGap> = emptyList(),
    val phases: List<ConfirmedPhaseEvent> = emptyList(),
    val zoneDurations: ZoneDurations? = null,
    val zoneIntervals: List<ZoneInterval> = emptyList()
)
data class SessionSummary(
    val session: ProcessingSession, val endedAtNanos: Long,
    val restingHeartRate: MetricResult<Double>, val exerciseHeartRate: MetricResult<ExerciseHeartRate>,
    val recovery: MetricResult<RecoveryRate>, val zoneDurations: ZoneDurations?,
    val phases: List<ConfirmedPhaseEvent>
)

/** Display history only: dropping old points never changes metric accumulators. */
class ChartBuffer {
    private val hr = ArrayDeque<ChartPoint>()
    private val rms = ArrayDeque<ChartPoint>()
    private val gaps = ArrayDeque<DataGap>()
    private var lastHr: Long? = null
    private var lastAcceleration: Long? = null
    private var lastRmsBucket: Long? = null
    private fun gap(stream: String, start: Long, end: Long?, reason: String) {
        gaps.addLast(DataGap(stream,start,end,reason))
        while(gaps.size > 100) gaps.removeFirst()
    }
    private fun sample(stream: String, time: Long, previous: Long?, hold: Long) {
        if (previous != null && time - previous > hold) gap(stream,previous+hold,time,"Sample gap")
        val open = gaps.indexOfLast { it.stream == stream && it.endNanos == null }
        if(open >= 0) gaps[open] = gaps[open].copy(endNanos = maxOf(time,gaps[open].startNanos))
    }
    fun heartRate(sample: HeartRateInput) {
        if(lastHr?.let { sample.timestampNanos <= it } == true) return
        sample("HR",sample.timestampNanos,lastHr,SensorPreprocessor.HEART_RATE_HOLD_NANOS)
        lastHr = sample.timestampNanos
        val value=sample.bpm.takeIf { it.isFinite() && it > 0 }
        hr.addLast(ChartPoint(sample.timestampNanos,value,sample.source))
        if(value == null) gap("HR",sample.timestampNanos,sample.timestampNanos,"Invalid value")
        while(hr.size > 43_201 || (hr.isNotEmpty() &&
            hr.first().timestampNanos < sample.timestampNanos - 43_200_000_000_000L)) hr.removeFirst()
    }
    fun acceleration(sample: AccelerationInput, result: MotionResult) {
        if(lastAcceleration?.let { sample.timestampNanos <= it } == true) return
        sample("Acceleration",sample.timestampNanos,lastAcceleration,SensorPreprocessor.ACCELERATION_HOLD_NANOS)
        lastAcceleration=sample.timestampNanos
        if (!sample.x.isFinite() || !sample.y.isFinite() || !sample.z.isFinite())
            gap("Acceleration",sample.timestampNanos,sample.timestampNanos,"Invalid value")
        val value=(result.rms as? MetricResult.Available)?.value
        val bucket=sample.timestampNanos / 1_000_000_000L
        val point=ChartPoint(sample.timestampNanos,value,sample.source)
        if(lastRmsBucket == bucket && rms.isNotEmpty()) rms.removeLast()
        rms.addLast(point); lastRmsBucket=bucket
        while(rms.size > 43_201 || (rms.isNotEmpty() &&
            rms.first().timestampNanos < sample.timestampNanos - 43_200_000_000_000L)) rms.removeFirst()
    }
    fun interrupted(stream: String, time: Long) {
        if(gaps.none { it.stream == stream && it.endNanos == null }) gap(stream,time,null,"Reception unavailable")
    }
    fun snapshot(phases: List<ConfirmedPhaseEvent>, durations: ZoneDurations?) =
        ChartOutput(hr.toList(),rms.toList(),gaps.toList(),phases.toList(),durations)
}
