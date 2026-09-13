package com.example.mobile_wearableapplication

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mobile_wearableapplication.processing.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Hour buckets are display summaries; Part D's metric calculations stay unchanged. */
@Composable
internal fun HourlyHistoryChart(processing: ProcessingSnapshot, now: Long, offset: Long?, kind: String) {
    val hourMillis = 3_600_000L
    val currentHour = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val start = currentHour - 11 * hourMillis
    val sums = DoubleArray(12)
    val counts = IntArray(12)
    val zones = Array(12) { DoubleArray(5) }
    val colors = listOf(Color(0xFF00DDE7), Color(0xFFFFD166), Color(0xFFFF7973), Color(0xFFADB5BD), Color(0xFF69737D))
    val stacked = kind == "Intensity"
    val points = if (kind == "HR") processing.chartOutput.heartRate else processing.chartOutput.rms
    if (offset != null) {
        if (stacked) {
            processing.chartOutput.zoneIntervals.forEach { interval ->
                val from = interval.startNanos / 1_000_000L + offset
                val to = minOf(now, interval.endNanos / 1_000_000L + offset)
                for (i in 0..11) {
                    val duration = minOf(to, start + (i + 1) * hourMillis) - maxOf(from, start + i * hourMillis)
                    if (duration > 0) zones[i][interval.zone.ordinal] += duration / 60_000.0
                }
            }
        } else points.forEach { point ->
            val time = point.timestampNanos / 1_000_000L + offset
            val value = point.value
            if (time in start..now && value != null && value.isFinite()) {
                val index = ((time - start) / hourMillis).toInt()
                if (index in 0..11) { sums[index] += value; counts[index]++ }
            }
        }
    }
    val means = List(12) { if (counts[it] == 0) null else sums[it] / counts[it] }
    val low = if (kind == "HR") 40.0 else 0.0
    val high = when (kind) {
        "HR" -> 200.0
        "Intensity" -> 60.0
        else -> maxOf(1.0, (means.filterNotNull().maxOrNull() ?: 0.0) * 1.1)
    }
    val unit = when (kind) { "HR" -> "bpm"; "RMS" -> "m/s²"; else -> "min" }
    val format = SimpleDateFormat("HH:mm", Locale.getDefault())
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        val left = 38.dp.toPx(); val right = size.width - 12.dp.toPx()
        val top = 20.dp.toPx(); val bottom = size.height - 28.dp.toPx()
        if (right <= left) return@Canvas
        val step = (right - left) / 12
        fun x(i: Int) = left + (i + 0.5f) * step
        fun y(value: Double) = bottom - ((value - low) / (high - low) * (bottom - top)).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.LTGRAY; textSize = 10.sp.toPx() }
        fun label(text: String, x: Float, y: Float) { drawContext.canvas.nativeCanvas.drawText(text, x, y, paint) }
        label(unit, 0f, 12.dp.toPx())
        for (i in 0..4) {
            val value = low + (high - low) * i / 4
            drawLine(Color(0xFF455057), Offset(left, y(value)), Offset(right, y(value)))
            label(String.format(Locale.US, if (kind == "RMS") "%.1f" else "%.0f", value), 0f, y(value))
        }
        for (i in listOf(0, 3, 6, 9, 11)) {
            val text = format.format(Date(start + i * hourMillis))
            label(text, (x(i) - paint.measureText(text) / 2).coerceIn(0f, (size.width - paint.measureText(text)).coerceAtLeast(0f)), size.height - 5.dp.toPx())
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        if (kind == "HR" && offset != null) {
            val events = processing.chartOutput.phases.mapIndexed { index, phase ->
                phase.watchElapsedTimeNanos to "${index + 1}"
            } + listOfNotNull(processing.recoveryStartedAt?.let { (it + 60_000_000_000L) to "+60s" })
            events.forEach { (time, name) ->
                val wall = time / 1_000_000L + offset
                if (wall in start..now) {
                    val eventX = left + ((wall - start).toDouble() / hourMillis * step).toFloat()
                    drawLine(Color.Gray, Offset(eventX, top), Offset(eventX, bottom), 1.dp.toPx(), pathEffect = dash)
                    label(name, eventX.coerceAtMost(right - paint.measureText(name)), top - 4.dp.toPx())
                }
            }
        }
        clipRect(left, top, right, bottom) {
            if (stacked) {
                for (i in 0..11) {
                    var base = 0.0
                    zones[i].forEachIndexed { zone, duration ->
                        if (duration > 0) drawRect(colors[zone], Offset(x(i) - step * 0.32f, y(base + duration)),
                            Size(step * 0.64f, y(base) - y(base + duration)))
                        base += duration
                    }
                }
            } else {
                for (i in 0..11) {
                    val value = means[i] ?: continue
                    if (i > 0) means[i - 1]?.let { previous ->
                        drawLine(colors[0], Offset(x(i - 1), y(previous)), Offset(x(i), y(value)), 2.dp.toPx())
                    }
                    drawCircle(colors[0], 3.dp.toPx(), Offset(x(i), y(value)))
                }
                val references = if (kind == "RMS") listOf(0.3, 0.8)
                    else listOfNotNull((processing.restingHeartRate as? MetricResult.Available)?.value)
                references.forEach { value ->
                    if (value in low..high) drawLine(colors[1], Offset(left, y(value)), Offset(right, y(value)), 1.dp.toPx(), pathEffect = dash)
                }
            }
        }
    }
    Text("12 hourly buckets · current hour is partial", color = Color.LightGray, fontSize = 12.sp)
    if (stacked) {
        IntensityZone.entries.forEachIndexed { i, zone ->
            Text(zone.name.lowercase().replaceFirstChar { it.uppercase() }, color = colors[i], fontSize = 12.sp)
        }
        if (zones.all { row -> row.all { it == 0.0 } }) Text("No exercise duration in this window", color = Color.LightGray)
    } else {
        Text("Hourly mean of available ${if (kind == "HR") "raw HR" else "1-second RMS"} samples; empty hours break the line", color = Color.LightGray, fontSize = 12.sp)
        if (kind == "RMS") Text("${processing.motion.state} · RMS of |acceleration magnitude − 9.81|\nEngineering thresholds: 0.3 / 0.8 m/s²", color = Color.LightGray, fontSize = 12.sp)
        if (kind == "HR") {
            if (offset != null) processing.chartOutput.phases.forEachIndexed { index, phase ->
                Text("${index + 1}: ${phase.phase.name.lowercase()} · ${format.format(Date(phase.watchElapsedTimeNanos / 1_000_000L + offset))}", color = Color.LightGray, fontSize = 12.sp)
            }
            if (processing.recoveryStartedAt != null) Text("+60s: recovery target marker appears once reached", color = Color.LightGray, fontSize = 12.sp)
            (processing.restingHeartRate as? MetricResult.Available)?.let {
                Text(String.format(Locale.US, "Yellow: resting baseline %.1f bpm", it.value), color = colors[1], fontSize = 12.sp)
            }
        }
        if (counts.all { it == 0 }) Text("No valid samples in this window", color = Color.LightGray)
    }
    Text("Current session, retained data only · clock time approximated from phone receipt", color = Color.Gray, fontSize = 12.sp)
}
