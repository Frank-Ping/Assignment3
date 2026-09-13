package com.example.mobile_wearableapplication

import com.example.shared.communication.SessionAction

import com.example.mobile_wearableapplication.processing.ProcessingSnapshot

internal data class HistoryPreview(val processing: ProcessingSnapshot, val epochOffsetMillis: Long)
internal data class HeartRatePreview(val bpm: Double, val receivedAtMillis: Long)

/** Written only by the debug source set's ADB receiver. Never enters live processing. */
internal object HistoryPreviewStore {
    var sessionAction: ((String) -> String)? = null
    @Volatile var value: HistoryPreview? = null
    @Volatile var heartRate: HeartRatePreview? = null
}
