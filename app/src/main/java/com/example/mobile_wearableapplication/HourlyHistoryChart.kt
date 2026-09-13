package com.example.mobile_wearableapplication

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
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
internal fun HourlyHistoryChart(processing: ProcessingSnapshot, now: Long, offset: Long?, kind: String, storedHours: List<StoredHour>? = null) {
    val hourMillis = 3_600_000L
    val currentHour = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val start = currentHour - 11 * hourMillis
    val colors = listOf(Color(0xFF00DDE7), Color(0xFFFFD166), Color(0xFFFF7973), Color(0xFFADB5BD), Color(0xFF69737D))
    val stacked = kind == "Intensity"
    val points = if (kind == "HR") processing.chartOutput.heartRate else processing.chartOutput.rms
    // Refresh aggregation only for a new snapshot/time tick, not unrelated UI changes.
    val (sums, counts, zones) = remember(processing.chartOutput, offset, start, now / 1_000, kind, storedHours) {
        val sums = DoubleArray(12)
        val counts = IntArray(12)
        val zones = Array(12) { DoubleArray(5) }
    if (storedHours != null) {
        storedHours.forEach { row ->
            if (row.time in start..now) {
                val index = ((row.time - start) / hourMillis).toInt()
                if (index in 0..11) {
                    sums[index] += row.sum
                    counts[index] += row.count.toInt()
                    row.zones.forEachIndexed { zone, seconds -> zones[index][zone] += seconds / 60.0 }
                }
            }
        }
    } else if (offset != null) {
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
        Triple(sums, counts, zones)
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
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Canvas(Modifier.fillMaxWidth().weight(1f)) {
        val left = 42.dp.toPx(); val right = size.width - 12.dp.toPx()
        val top = 20.dp.toPx(); val bottom = size.height - 28.dp.toPx()
        if (right <= left || bottom <= top) return@Canvas
        val step = (right - left) / 12
        fun x(i: Int) = left + (i + 0.5f) * step
        fun y(value: Double) = bottom - ((value - low) / (high - low) * (bottom - top)).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.LTGRAY; textSize = 10.sp.toPx(); isFakeBoldText = true }
        fun label(text: String, x: Float, y: Float) { drawContext.canvas.nativeCanvas.drawText(text, x, y, paint) }
        label(unit, 0f, 12.dp.toPx())
        val verticalTicks = if ((bottom - top) / 4 < paint.fontSpacing * 1.4f) listOf(0, 2, 4) else (0..4).toList()
        for (i in verticalTicks) {
            val value = low + (high - low) * i / 4
            drawLine(Color(0xFF455057), Offset(left, y(value)), Offset(right, y(value)))
            val text = String.format(Locale.US, if (kind == "RMS") "%.1f" else "%.0f", value)
            label(text, left - paint.measureText(text) - 7.dp.toPx(), y(value) - (paint.ascent() + paint.descent()) / 2)
        }
        val grid = Color(0xFF455057)
        for (i in 0..12) {
            val gridX = left + i * step
            drawLine(grid, Offset(gridX, top), Offset(gridX, bottom), 0.5.dp.toPx())
        }
        drawRect(Color(0xFF71808A), Offset(left, top), Size(right - left, bottom - top), style = Stroke(1.dp.toPx()))
        var previousLabelEnd = -Float.MAX_VALUE
        for (i in listOf(0, 3, 6, 9, 11)) {
            val text = format.format(Date(start + i * hourMillis))
            val labelX = (x(i) - paint.measureText(text) / 2).coerceIn(0f, (size.width - paint.measureText(text)).coerceAtLeast(0f))
            if (labelX >= previousLabelEnd + 8.dp.toPx()) {
                label(text, labelX, size.height - 5.dp.toPx())
                previousLabelEnd = labelX + paint.measureText(text)
            }
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        if (kind == "HR" && offset != null) {
            val events = processing.chartOutput.phases.mapIndexed { index, phase ->
                phase.watchElapsedTimeNanos to "${index + 1}"
            } + listOfNotNull(processing.recoveryStartedAt?.let { (it + 60_000_000_000L) to "+60s" })
            var previousEventEnd = -Float.MAX_VALUE
            events.forEach { (time, name) ->
                val wall = time / 1_000_000L + offset
                if (wall in start..now) {
                    val eventX = left + ((wall - start).toDouble() / hourMillis * step).toFloat()
                    drawLine(Color.Gray, Offset(eventX, top), Offset(eventX, bottom), 1.dp.toPx(), pathEffect = dash)
                    val labelX = eventX.coerceAtMost(right - paint.measureText(name))
                    if (labelX >= previousEventEnd + 5.dp.toPx()) {
                        label(name, labelX, top - 4.dp.toPx())
                        previousEventEnd = labelX + paint.measureText(name)
                    }
                }
            }
        }
        clipRect(left, top, right, bottom) {
            if (stacked) {
                for (i in 0..11) {
                    var base = 0.0
                    zones[i].take(3).forEachIndexed { zone, duration ->
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
        // Reserve identical legend space so switching tabs does not resize the plot.
        Row(Modifier.fillMaxWidth().alpha(if (stacked) 1f else 0f), horizontalArrangement = Arrangement.SpaceEvenly) {
            IntensityZone.entries.take(3).forEachIndexed { i, zone ->
                Text("● ${zone.name.lowercase().replaceFirstChar { it.uppercase() }}", color = colors[i], fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}
