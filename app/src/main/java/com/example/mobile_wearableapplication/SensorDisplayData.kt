package com.example.mobile_wearableapplication

import com.example.shared.communication.WireDataType
import com.example.shared.communication.SessionLifecycle

import android.os.SystemClock
import com.example.mobile_wearableapplication.communication.*
import com.example.mobile_wearableapplication.processing.*

/** One display refresh; metrics and chart history remain owned by Part D. */
internal data class SensorDisplayData(
    val processing: ProcessingSnapshot,
    val session: ReceivedSessionSnapshot?,
    val reception: ReceptionDiagnostics,
    val readAtMillis: Long
) {
    fun heartRateUnavailableReason(): String? {
        if (processing.session == null) return "No session"
        if (!reception.ready) return "Historical / ${reception.message}"
        if (reception.sessionLifecycle != SessionLifecycle.RUNNING) return "Historical / collection ended"
        val stream = session?.streams?.get(WireDataType.HEART_RATE) ?: return "Waiting for data"
        if (!stream.freshSinceResume) return "Waiting for new sample"
        val receipt = stream.lastNewSampleAtMillis ?: return "Waiting for data"
        // Match the existing receipt watchdog, not cross-device measurement clocks.
        if (readAtMillis - receipt > 3_000L) return "Stale / no new sample"
        val sample = stream.latest?.sample ?: return "Waiting for data"
        val valid = sample.bpm?.let { it.isFinite() && it > 0 } == true
        return if (valid) null else "Invalid data"
    }

    val currentHeartRateBpm: Double?
        get() = if (heartRateUnavailableReason() == null)
            session?.streams?.get(WireDataType.HEART_RATE)?.latest?.sample?.bpm else null

    companion object {
        fun read(): SensorDisplayData = synchronized(ReceivedSensorStore) {
            SensorDisplayData(
                processing = ReceivedSensorStore.processingSnapshot(),
                session = ReceivedSensorStore.snapshot(),
                reception = ReceivedSensorStore.receptionDiagnostics(),
                readAtMillis = SystemClock.elapsedRealtime()
            )
        }
    }
}
