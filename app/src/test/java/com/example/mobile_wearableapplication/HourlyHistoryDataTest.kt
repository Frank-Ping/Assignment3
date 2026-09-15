package com.example.mobile_wearableapplication

import com.example.mobile_wearableapplication.processing.*
import org.junit.Assert.*
import org.junit.Test

class HourlyHistoryDataTest {
    private val hour = HISTORY_HOUR_MILLIS
    // Starts at 20:00 UTC, so the twelve buckets also exercise a date boundary.
    private val start = 20 * hour
    private fun point(millis: Long, value: Double?, source: SampleSource = SampleSource.REAL) =
        ChartPoint(millis * 1_000_000L, value, source)
    private fun aggregate(points: List<ChartPoint>, now: Long = start + 11 * hour + hour / 2) =
        aggregateHourlyHistory(ChartOutput(heartRate = points), start, now, start, null)

    @Test fun groupsExtremaByHourRatherThanAveraging() {
        val buckets = aggregate(listOf(point(0, 80.0), point(1000, 65.0), point(hour - 1, 140.0),
            point(hour, 90.0), point(4 * hour, 72.0)))
        assertEquals(12, buckets.size)
        assertEquals(65.0, buckets[0].minBpm!!, 0.0)
        assertEquals(140.0, buckets[0].maxBpm!!, 0.0)
        assertEquals(90.0, buckets[1].minBpm!!, 0.0)
        assertEquals(90.0, buckets[1].maxBpm!!, 0.0)
        assertNull(buckets[2].minBpm)
        assertNull(buckets[3].maxBpm)
        assertEquals(72.0, buckets[4].minBpm!!, 0.0)
    }

    @Test fun excludesOldFutureAndInvalidReadingsButIncludesNow() {
        val now = start + 11 * hour + 5000
        val buckets = aggregate(listOf(point(-1, 20.0), point(0, null), point(1, Double.NaN),
            point(2, Double.POSITIVE_INFINITY), point(3, 0.0), point(4, -1.0),
            point(11 * hour, 45.0), point(11 * hour + 5000, 230.0),
            point(11 * hour + 5001, 299.0), point(12 * hour, 300.0)), now)
        assertNull(buckets[0].minBpm)
        assertEquals(45.0, buckets[11].minBpm!!, 0.0)
        assertEquals(230.0, buckets[11].maxBpm!!, 0.0)
    }

    @Test fun mergesSessionsAndSourcesWithinAnHour() {
        val rows = listOf(StoredHour(start, 65.0, 100.0, listOf(60.0, 0.0, 0.0)),
            StoredHour(start, 70.0, 155.0, listOf(0.0, 120.0, 0.0)),
            StoredHour(start + hour, null, null, listOf(0.0, 0.0, 60.0)))
        val buckets = aggregateHourlyHistory(ChartOutput(), start, start + 11 * hour, null, rows)
        assertEquals(65.0, buckets[0].minBpm!!, 0.0)
        assertEquals(155.0, buckets[0].maxBpm!!, 0.0)
        assertEquals(listOf(1.0, 2.0, 0.0), buckets[0].zoneMinutes)
        assertNull(buckets[1].minBpm)
        assertEquals(listOf(0.0, 0.0, 1.0), buckets[1].zoneMinutes)
    }

    @Test fun legacyHourWithoutExtremaCannotMasqueradeAsCompleteRange() {
        val rows = listOf(StoredHour(start, null, null, listOf(120.0, 0.0, 0.0), false),
            StoredHour(start, 80.0, 120.0, emptyList()),
            StoredHour(start + hour, 70.0, 100.0, emptyList()))
        val buckets = aggregateHourlyHistory(ChartOutput(), start, start + hour, null, rows)
        assertNull(buckets[0].minBpm)
        assertNull(buckets[0].maxBpm)
        assertEquals(2.0, buckets[0].zoneMinutes[0], 0.0)
        assertEquals(70.0, buckets[1].minBpm!!, 0.0)
    }

    @Test fun storedHistoryIsNotDoubleCountedWithLiveBuffer() {
        val rows = listOf(StoredHour(start, 60.0, 110.0, emptyList()))
        val buckets = aggregateHourlyHistory(ChartOutput(heartRate = listOf(point(0, 200.0))),
            start, start + hour, start, rows)
        assertEquals(110.0, buckets[0].maxBpm!!, 0.0)
    }

    @Test fun intensityIntervalsStillClipAtHourBoundariesAndNow() {
        val chart = ChartOutput(zoneIntervals = listOf(
            ZoneInterval((hour - 60_000) * 1_000_000L, (hour + 120_000) * 1_000_000L, IntensityZone.LOW),
            ZoneInterval(0, 60_000_000_000L, IntensityZone.MISSING)))
        val buckets = aggregateHourlyHistory(chart, start, start + hour + 60_000, start, null)
        assertEquals(listOf(1.0, 0.0, 0.0), buckets[0].zoneMinutes)
        assertEquals(listOf(1.0, 0.0, 0.0), buckets[1].zoneMinutes)
        assertNull(buckets[0].maxBpm)
    }

    @Test fun noTimeAnchorProducesEmptyLiveBuckets() {
        val buckets = aggregateHourlyHistory(ChartOutput(heartRate = listOf(point(0, 80.0))),
            start, start + hour, null, null)
        assertTrue(buckets.all { it.minBpm == null && it.maxBpm == null })
    }
}
