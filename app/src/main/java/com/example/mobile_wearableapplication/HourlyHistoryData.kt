package com.example.mobile_wearableapplication

import com.example.mobile_wearableapplication.processing.ChartOutput

internal const val HISTORY_HOUR_MILLIS = 3_600_000L

internal data class StoredHour(
    val time: Long,
    val minBpm: Double?,
    val maxBpm: Double?,
    val zones: List<Double>,
    val rangeComplete: Boolean = true
)

internal data class HourlyHistoryBucket(
    val minBpm: Double?,
    val maxBpm: Double?,
    val zoneMinutes: List<Double>
)

/** Twelve hour buckets, including the current partial hour. Missing hours remain null. */
internal fun aggregateHourlyHistory(
    chart: ChartOutput,
    start: Long,
    now: Long,
    offset: Long?,
    storedHours: List<StoredHour>?
): List<HourlyHistoryBucket> {
    val minima = arrayOfNulls<Double>(12)
    val maxima = arrayOfNulls<Double>(12)
    val complete = BooleanArray(12) { true }
    val zones = Array(12) { DoubleArray(3) }
    fun include(index: Int, minimum: Double?, maximum: Double?) {
        if (minimum == null || maximum == null || !minimum.isFinite() || !maximum.isFinite() ||
            minimum <= 0 || maximum < minimum) return
        minima[index] = minOf(minima[index] ?: minimum, minimum)
        maxima[index] = maxOf(maxima[index] ?: maximum, maximum)
    }
    if (storedHours != null) {
        storedHours.forEach { row ->
            if (row.time !in start..now) return@forEach
            val index = ((row.time - start) / HISTORY_HOUR_MILLIS).toInt()
            if (index !in 0..11) return@forEach
            complete[index] = complete[index] && row.rangeComplete
            include(index, row.minBpm, row.maxBpm)
            row.zones.take(3).forEachIndexed { zone, seconds -> zones[index][zone] += seconds / 60.0 }
        }
    } else if (offset != null) {
        chart.heartRate.forEach { point ->
            val time = point.timestampNanos / 1_000_000L + offset
            if (time !in start..now) return@forEach
            val index = ((time - start) / HISTORY_HOUR_MILLIS).toInt()
            if (index in 0..11) include(index, point.value, point.value)
        }
        chart.zoneIntervals.filter { it.zone.ordinal < 3 }.forEach { interval ->
            val from = interval.startNanos / 1_000_000L + offset
            val to = minOf(now, interval.endNanos / 1_000_000L + offset)
            for (i in 0..11) {
                val duration = minOf(to, start + (i + 1) * HISTORY_HOUR_MILLIS) -
                    maxOf(from, start + i * HISTORY_HOUR_MILLIS)
                if (duration > 0) zones[i][interval.zone.ordinal] += duration / 60_000.0
            }
        }
    }
    return List(12) { i ->
        HourlyHistoryBucket(minima[i].takeIf { complete[i] }, maxima[i].takeIf { complete[i] }, zones[i].toList())
    }
}
