package com.example.mobile_wearableapplication

import android.os.SystemClock
import com.example.mobile_wearableapplication.communication.*
import com.example.mobile_wearableapplication.processing.*

/** One display refresh; metrics and chart history remain owned by Part D. */
internal data class SensorDisplayData(
    val processing: ProcessingSnapshot,
    val session: ReceivedSessionSnapshot?,
    val reception: ReceptionDiagnostics,
    val lastSummary: SessionSummary?,
    val readAtMillis: Long
) {
    val charts get() = processing.chartOutput
    val currentSummary get() = processing.finalSummary

    fun unavailableReason(type: WireDataType): String? {
        if (processing.session == null) return "No session"
        if (!reception.ready) return "Historical / ${reception.message}"
        if (reception.sessionLifecycle != SessionLifecycle.RUNNING) return "Historical / collection ended"
        val stream = session?.streams?.get(type) ?: return "Waiting for data"
        if (!stream.freshSinceResume) return "Waiting for new sample"
        val receipt = stream.lastNewSampleAtMillis ?: return "Waiting for data"
        // Match the existing receipt watchdog, not cross-device measurement clocks.
        val holdMillis = if (type == WireDataType.HEART_RATE) 3_000L else 1_000L
        if (readAtMillis - receipt > holdMillis) return "Stale / no new sample"
        val sample = stream.latest?.sample ?: return "Waiting for data"
        val valid = if (type == WireDataType.HEART_RATE) {
            sample.bpm?.let { it.isFinite() && it > 0 } == true
        } else listOf(sample.x, sample.y, sample.z).all { it?.isFinite() == true }
        return if (valid) null else "Invalid data"
    }

    val currentHeartRateBpm: Double?
        get() = if (unavailableReason(WireDataType.HEART_RATE) == null)
            session?.streams?.get(WireDataType.HEART_RATE)?.latest?.sample?.bpm else null

    companion object {
        fun read(): SensorDisplayData = synchronized(ReceivedSensorStore) {
            SensorDisplayData(
                processing = ReceivedSensorStore.processingSnapshot(),
                session = ReceivedSensorStore.snapshot(),
                reception = ReceivedSensorStore.receptionDiagnostics(),
                lastSummary = ReceivedSensorStore.lastCompletedSummary(),
                readAtMillis = SystemClock.elapsedRealtime()
            )
        }
    }
}
