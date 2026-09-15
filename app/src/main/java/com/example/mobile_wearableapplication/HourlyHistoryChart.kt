package com.example.mobile_wearableapplication

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
    val hourMillis = HISTORY_HOUR_MILLIS
    val currentHour = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val start = currentHour - 11 * hourMillis
    val colors = listOf(Color(0xFF00DDE7), Color(0xFFFFD166), Color(0xFFFF7973))
    val rangeColor = Color(0xFFD5D5DE)
    val stacked = kind == "Intensity"
    val buckets = remember(processing.chartOutput, offset, start, now / 1_000, storedHours) {
        aggregateHourlyHistory(processing.chartOutput, start, now, offset, storedHours)
    }
    // Keep the usual HR scale, expanding it when necessary so extrema are never clipped.
    val minimum = buckets.mapNotNull { it.minBpm }.minOrNull() ?: 40.0
    val maximum = buckets.mapNotNull { it.maxBpm }.maxOrNull() ?: 200.0
    val low = if (stacked) 0.0 else minOf(40.0, kotlin.math.floor(minimum / 20.0) * 20.0 - 20.0).coerceAtLeast(0.0)
    val high = if (stacked) 60.0 else maxOf(200.0, kotlin.math.ceil(maximum / 20.0) * 20.0 + 20.0)
    val unit = if (stacked) "min" else "bpm"
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
            val text = String.format(Locale.US, "%.0f", value)
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
        clipRect(left, top, right, bottom) {
            if (stacked) {
                for (i in 0..11) {
                    var base = 0.0
                    buckets[i].zoneMinutes.forEachIndexed { zone, duration ->
                        if (duration > 0) drawRect(colors[zone], Offset(x(i) - step * 0.32f, y(base + duration)),
                            Size(step * 0.64f, y(base) - y(base + duration)))
                        base += duration
                    }
                }
            } else {
                val barWidth = minOf(10.dp.toPx(), step * 0.38f)
                buckets.forEachIndexed { i, bucket ->
                    val minBpm = bucket.minBpm
                    val maxBpm = bucket.maxBpm
                    if (minBpm != null && maxBpm != null) {
                        val barTop = y(maxBpm)
                        val barHeight = y(minBpm) - barTop
                        if (barHeight < 1f) {
                            // A single value has no range; show a dot at that value.
                            drawCircle(rangeColor, barWidth / 2, Offset(x(i), barTop))
                        } else {
                            val radius = minOf(barWidth, barHeight) / 2
                            drawRoundRect(rangeColor, Offset(x(i) - barWidth / 2, barTop),
                                Size(barWidth, barHeight), CornerRadius(radius, radius))
                        }
                    }
                }
            }
        }
    }
        // Both tabs reserve a legend row, so switching does not resize the plot.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (stacked) {
                IntensityZone.entries.take(3).forEachIndexed { i, zone ->
                    Text("● ${zone.name.lowercase().replaceFirstChar { it.uppercase() }}", color = colors[i], fontSize = 11.sp, maxLines = 1)
                }
            } else {
                Text("● Hourly Min–Max", color = rangeColor, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}
