package com.example.mobile_wearableapplication

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mobile_wearableapplication.processing.*
import java.util.Locale

/** Bounded display history only; never calculates physiological metrics. */
@Composable
internal fun HeartRateChart(processing: ProcessingSnapshot, nowMillis: Long, epochOffsetMillis: Long?) {
    val chart = processing.chartOutput
    val points = chart.heartRate
    val offset = epochOffsetMillis ?: nowMillis
    val end = (nowMillis - offset) * 1_000_000L
    val start = end - 12L * 60 * 60 * 1_000_000_000L
    val recoveryEnd = processing.recoveryStartedAt?.plus(60_000_000_000L)
    val baseline = (processing.restingHeartRate as? MetricResult.Available)?.value?.takeIf { it.isFinite() }
    val low = 40.0
    val high = 200.0
    val gaps = chart.gaps.filter { it.stream == "HR" }
    fun clockTime(time: Long): String = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
        .format(java.util.Date(time / 1_000_000L + offset))
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        val left = 38.dp.toPx()
        val right = size.width - 12.dp.toPx()
        val top = 22.dp.toPx()
        val bottom = size.height - 28.dp.toPx()
        if (right <= left) return@Canvas
        fun x(time: Long) = left + ((time - start).toDouble() / (end - start) * (right - left)).toFloat()
        fun y(value: Double) = bottom - ((value - low) / (high - low) * (bottom - top)).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.LTGRAY
            textSize = 10.sp.toPx()
        }
        fun label(text: String, x: Float, y: Float) = drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
        label("bpm", 0f, 12.dp.toPx())
        for (value in listOf(40.0, 80.0, 120.0, 160.0, 200.0)) {
            drawLine(Color(0xFF455057), Offset(left, y(value)), Offset(right, y(value)), 1.dp.toPx())
            label(String.format(Locale.US, "%.0f", value), 0f, y(value))
        }
        for (hour in 0..12 step 3) {
            val time = start + hour * 60L * 60 * 1_000_000_000L
            val text = clockTime(time)
            label(text, (x(time) - paint.measureText(text) / 2).coerceIn(0f, (size.width - paint.measureText(text)).coerceAtLeast(0f)), size.height - 6.dp.toPx())
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        baseline?.takeIf { it in low..high }?.let {
            drawLine(Color(0xFFFF7973), Offset(left, y(it)), Offset(right, y(it)), 1.dp.toPx(), pathEffect = dash)
        }
        chart.phases.forEachIndexed { index, phase ->
            val time = phase.watchElapsedTimeNanos
            if (time in start..end) {
                drawLine(Color.Gray, Offset(x(time), top), Offset(x(time), bottom), 1.dp.toPx(), pathEffect = dash)
                label("${index + 1}", x(time), top - 4.dp.toPx())
            }
        }
        recoveryEnd?.let { time ->
            if (time in start..end) {
                drawLine(Color(0xFFFFD166), Offset(x(time), top), Offset(x(time), bottom), 1.dp.toPx(), pathEffect = dash)
                label("+60s", (x(time) - paint.measureText("+60s")).coerceAtLeast(left), top - 4.dp.toPx())
            }
        }
        clipRect(left, top, right, bottom) {
        var previous: ChartPoint? = null
        points.forEach { point ->
            val value = point.value
            if (value == null || !value.isFinite()) {
                previous = null
            } else {
                val before = previous
                if (before != null && before.source == point.source && gaps.none { gap ->
                    if (gap.endNanos == gap.startNanos) gap.startNanos in before.timestampNanos..point.timestampNanos
                    else gap.startNanos < point.timestampNanos && (gap.endNanos ?: Long.MAX_VALUE) > before.timestampNanos
                }) {
                    drawLine(Color(0xFF00DDE7), Offset(x(before.timestampNanos), y(checkNotNull(before.value))),
                        Offset(x(point.timestampNanos), y(value)), 2.dp.toPx())
                }
                drawCircle(Color(0xFF00DDE7), 2.dp.toPx(), Offset(x(point.timestampNanos), y(value)))
                previous = point
            }
        }
        }
    }
    Text("Past 12 hours · Raw HR · ${points.map { it.source }.distinct().joinToString()}", color = Color.LightGray, fontSize = 12.sp)
    chart.phases.forEachIndexed { index, phase ->
        Text("${index + 1}: ${phase.phase.name.lowercase().replaceFirstChar { it.uppercase() }} · ${clockTime(phase.watchElapsedTimeNanos)}",
            color = Color.LightGray, fontSize = 12.sp)
    }
    baseline?.let { Text(String.format(Locale.US, "Dashed coral: resting baseline %.1f bpm", it), color = Color(0xFFFF7973), fontSize = 12.sp) }
    recoveryEnd?.let { Text("Yellow: recovery +60s target · ${clockTime(it)}", color = Color(0xFFFFD166), fontSize = 12.sp) }
    if (gaps.isNotEmpty()) Text("HR gaps break the line", color = Color.LightGray, fontSize = 12.sp)
    if (points.isEmpty()) Text("No heart rate data", color = Color.LightGray, fontSize = 12.sp)
    Text("Clock times approximate phone receipt; current session only. Up to 600 retained points.", color = Color.Gray, fontSize = 12.sp)
}
