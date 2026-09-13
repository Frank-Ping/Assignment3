package com.example.mobile_wearableapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.mobile_wearableapplication.processing.*
import java.util.Calendar
import kotlin.random.Random

/** adb shell am broadcast -n <package>/.DebugHistoryReceiver --ei seed 551 */
class DebugHistoryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.hasExtra("sessionAction")) {
            resultData = HistoryPreviewStore.sessionAction?.invoke(intent.getStringExtra("sessionAction") ?: "")
                ?: "Open phone SensorActivity first"
            return
        }
        if (intent.getBooleanExtra("clearHeartRate", false)) {
            HistoryPreviewStore.heartRate = null
            resultData = "Live HR preview cleared"
            return
        }
        if (intent.hasExtra("bpm")) {
            val bpm = intent.getStringExtra("bpm")?.toDoubleOrNull()
            if (bpm == null || !bpm.isFinite() || bpm <= 0 || bpm > 300) {
                resultCode = 1
                resultData = "Use --es bpm with a number greater than 0 and at most 300"
                return
            }
            HistoryPreviewStore.heartRate = HeartRatePreview(bpm, android.os.SystemClock.elapsedRealtime())
            resultData = "Injected HR preview: $bpm bpm; fresh for 3 seconds"
            return
        }
        if (intent.getBooleanExtra("clear", false)) {
            HistoryPreviewStore.value = null
            resultData = "History preview cleared"
            return
        }
        val now = System.currentTimeMillis()
        val start = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis - 11 * 3_600_000L
        val random = Random(intent.getIntExtra("seed", 551))
        val hr = mutableListOf<ChartPoint>()
        val rms = mutableListOf<ChartPoint>()
        val intervals = mutableListOf<ZoneInterval>()
        // Minutes in Low / Moderate / High / Unclassified / Missing order.
        val schedule = listOf(
            intArrayOf(0,0,0,0,0), intArrayOf(0,0,0,0,0),
            intArrayOf(20,10,0,3,2), intArrayOf(10,30,12,3,5),
            intArrayOf(0,0,0,0,0), intArrayOf(0,0,0,0,60),
            intArrayOf(20,20,5,5,10), intArrayOf(5,15,35,3,2),
            intArrayOf(0,0,0,0,0), intArrayOf(25,20,5,5,5),
            intArrayOf(0,0,0,0,0), intArrayOf(15,25,15,3,2)
        )
        fun nanos(wall: Long) = (wall - start) * 1_000_000L
        val totals = DoubleArray(5)
        schedule.forEachIndexed { hour, durations ->
            val hourStart = start + hour * 3_600_000L
            var cursor = hourStart
            durations.forEachIndexed { zone, minutes ->
                val finish = minOf(cursor + minutes * 60_000L, now)
                if (finish > cursor) {
                    intervals.add(ZoneInterval(nanos(cursor), nanos(finish), IntensityZone.entries[zone]))
                    totals[zone] += (finish - cursor) / 1000.0
                }
                cursor += minutes * 60_000L
            }
            for (minute in 0..59) {
                val wall = hourStart + minute * 60_000L
                if (wall > now) break
                val time = nanos(wall)
                val zone = intervals.lastOrNull { time >= it.startNanos && time < it.endNanos }?.zone
                val missing = hour == 5 || zone == IntensityZone.MISSING
                val recovering = hour in listOf(4, 8, 10)
                val bpm = when (zone) {
                    IntensityZone.LOW -> 88.0
                    IntensityZone.MODERATE -> 120.0
                    IntensityZone.HIGH -> 162.0
                    else -> if (recovering) 110.0 - minute * 0.65 else 68.0
                }
                val movement = when (zone) {
                    IntensityZone.LOW -> 0.45
                    IntensityZone.MODERATE -> 1.0
                    IntensityZone.HIGH -> 1.8
                    else -> 0.1
                }
                hr.add(ChartPoint(time, if (missing) null else bpm + random.nextDouble(-3.0, 3.0), SampleSource.DEMO))
                rms.add(ChartPoint(time, if (missing) null else movement + random.nextDouble(0.0, 0.08), SampleSource.DEMO))
            }
        }
        val durations = ZoneDurations(totals[0], totals[1], totals[2], totals[3], totals[4])
        HistoryPreviewStore.value = HistoryPreview(
            ProcessingSnapshot(chartOutput = ChartOutput(heartRate = hr, rms = rms,
                zoneDurations = durations, zoneIntervals = intervals),
                restingHeartRate = MetricResult.Available(68.0, CalculationEvidence(sources = setOf(SampleSource.DEMO)))),
            start
        )
        resultData = "Loaded 12 hourly demo buckets; live readings unchanged"
    }
}
