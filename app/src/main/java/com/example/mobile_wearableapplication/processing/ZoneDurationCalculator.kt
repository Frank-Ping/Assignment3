package com.example.mobile_wearableapplication.processing

data class ZoneDurations(
    val lowSeconds: Double, val moderateSeconds: Double, val highSeconds: Double,
    val unclassifiedSeconds: Double, val missingSeconds: Double
) {
    val totalSeconds: Double get() = lowSeconds + moderateSeconds + highSeconds + unclassifiedSeconds + missingSeconds
}

/** Integrates confirmed states, never candidate states. Snapshot reads do not accumulate again. */
class ZoneDurationCalculator {
    private data class Event(val time: Long, val zone: IntensityZone)
    private val events = mutableListOf<Event>()
    fun record(time: Long, zone: IntensityZone) {
        if (time < 0 || events.lastOrNull()?.let { time <= it.time } == true) return
        events.add(Event(time, zone))
    }
    fun result(start: Long?, finish: Long?, now: Long?): ZoneDurations? {
        if (start == null) return null
        val end = maxOf(start, finish ?: now ?: start)
        val totals = LongArray(IntensityZone.entries.size)
        var cursor: Long = start
        var zone = IntensityZone.UNCLASSIFIED
        var expires = start + SensorPreprocessor.HEART_RATE_HOLD_NANOS
        fun addUntil(to: Long) {
            val heldEnd = minOf(to, expires).coerceAtLeast(cursor)
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
        return ZoneDurations(totals[IntensityZone.LOW.ordinal]/1e9,
            totals[IntensityZone.MODERATE.ordinal]/1e9, totals[IntensityZone.HIGH.ordinal]/1e9,
            totals[IntensityZone.UNCLASSIFIED.ordinal]/1e9, totals[IntensityZone.MISSING.ordinal]/1e9)
    }
}

