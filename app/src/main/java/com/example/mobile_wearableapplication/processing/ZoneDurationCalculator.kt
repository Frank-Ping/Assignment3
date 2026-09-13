package com.example.mobile_wearableapplication.processing

data class ZoneDurations(
    val lowSeconds: Double, val moderateSeconds: Double, val highSeconds: Double,
    val unclassifiedSeconds: Double, val missingSeconds: Double
) {
    val totalSeconds: Double get() = lowSeconds + moderateSeconds + highSeconds + unclassifiedSeconds + missingSeconds
}

data class ZoneInterval(val startNanos: Long, val endNanos: Long, val zone: IntensityZone)

/** Integrates confirmed states, never candidate states. Snapshot reads do not accumulate again. */
class ZoneDurationCalculator {
    private data class Event(val time: Long, val zone: IntensityZone)
    private val committedIntervals = mutableListOf<ZoneInterval>()
    private var displayIntervals = emptyList<ZoneInterval>()
    fun intervals(): List<ZoneInterval> = displayIntervals
    private fun append(target: MutableList<ZoneInterval>, start: Long, end: Long, zone: IntensityZone) {
        if (end <= start) return
        val previous = target.lastOrNull()
        if (previous?.zone == zone && previous.endNanos == start)
            target[target.lastIndex] = previous.copy(endNanos = end)
        else target.add(ZoneInterval(start, end, zone))
    }
    private var closedAt: Long? = null
    private var closedResult: ZoneDurations? = null
    private val events = mutableListOf<Event>()
    private var lastTime = -1L
    fun record(time: Long, zone: IntensityZone) {
        if (time < 0 || time <= lastTime || closedAt?.let { time >= it } == true) return
        lastTime = time
        events.add(Event(time, zone))
    }
    private var initializedStart: Long? = null
    private var committedCursor = 0L
    private var committedZone = IntensityZone.UNCLASSIFIED
    private var committedExpires = 0L
    private val committedTotals = LongArray(IntensityZone.entries.size)
    fun result(start: Long?, finish: Long?, now: Long?): ZoneDurations? {
        if (start == null) return null
        if (finish != null && closedAt == finish) closedResult?.let { return it }
        val end = maxOf(start, finish ?: now ?: start)
        if (initializedStart == null) {
            initializedStart = start; committedCursor = start
            committedExpires = start + SensorPreprocessor.HEART_RATE_HOLD_NANOS
        }
        if (initializedStart != start || end < committedCursor) return null
        while (events.isNotEmpty() && events.first().time < end - 65_000_000_000L) {
            val event = events.removeAt(0)
            if (event.time < committedCursor) continue
            val heldEnd = minOf(event.time, committedExpires).coerceAtLeast(committedCursor)
            append(committedIntervals, committedCursor, heldEnd, committedZone)
            append(committedIntervals, heldEnd, event.time, IntensityZone.MISSING)
            committedTotals[committedZone.ordinal] += heldEnd - committedCursor
            committedTotals[IntensityZone.MISSING.ordinal] += event.time - heldEnd
            committedCursor = event.time; committedZone = event.zone
            committedExpires = event.time + SensorPreprocessor.HEART_RATE_HOLD_NANOS
        }
        val cutoff = end - 43_200_000_000_000L
        while (committedIntervals.isNotEmpty() && committedIntervals.first().endNanos <= cutoff)
            committedIntervals.removeAt(0)
        val tail = mutableListOf<ZoneInterval>()
        val totals = committedTotals.copyOf()
        var cursor = committedCursor
        var zone = committedZone
        var expires = committedExpires
        fun addUntil(to: Long) {
            val heldEnd = minOf(to, expires).coerceAtLeast(cursor)
            append(tail, cursor, heldEnd, zone)
            append(tail, heldEnd, to, IntensityZone.MISSING)
            totals[zone.ordinal] += heldEnd - cursor
            totals[IntensityZone.MISSING.ordinal] += to - heldEnd
            cursor = to
        }
        for (event in events) {
            if (event.time < start) continue
            if (event.time >= end) break
            addUntil(event.time)
            zone = event.zone
            expires = event.time + SensorPreprocessor.HEART_RATE_HOLD_NANOS
        }
        addUntil(end)
        displayIntervals = committedIntervals.toList() + tail
        val result = ZoneDurations(totals[IntensityZone.LOW.ordinal]/1e9,
            totals[IntensityZone.MODERATE.ordinal]/1e9, totals[IntensityZone.HIGH.ordinal]/1e9,
            totals[IntensityZone.UNCLASSIFIED.ordinal]/1e9, totals[IntensityZone.MISSING.ordinal]/1e9)
        if (finish != null) { closedAt = finish; closedResult = result }
        return result
    }
}

