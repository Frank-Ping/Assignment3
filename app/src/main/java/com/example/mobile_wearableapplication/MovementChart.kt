package com.example.mobile_wearableapplication

import androidx.compose.runtime.Composable
import com.example.mobile_wearableapplication.processing.ProcessingSnapshot

@Composable
internal fun MovementChart(processing: ProcessingSnapshot, nowMillis: Long, epochOffsetMillis: Long?) {
    HourlyHistoryChart(processing, nowMillis, epochOffsetMillis, "RMS")
}
