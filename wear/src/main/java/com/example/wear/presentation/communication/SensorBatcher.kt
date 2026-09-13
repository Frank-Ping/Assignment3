package com.example.wear.presentation.communication

import com.example.shared.communication.WireDataType
import com.example.shared.communication.WireSource
import com.example.shared.communication.WireSample
import com.example.shared.communication.SensorBatch
import com.example.shared.communication.CommunicationProtocol

import android.os.Handler
import android.os.Looper
import com.example.wear.presentation.data.AccelerometerRecord
import com.example.wear.presentation.data.HeartRateRecord
import java.util.UUID

/** Main-thread adapter. Disconnected samples are discarded, not replayed. */
class SensorBatcher(
    private val nodeId: () -> String?,
    private val sender: SensorDataSender,
    private val report: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val acceleration = mutableListOf<WireSample>()
    private var sessionId = ""
    private var accelerationSource = WireSource.REAL
    private var running = false
    private var discarded = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            flush()
            handler.postDelayed(this, 500L)
        }
    }

    fun start(confirmedSessionId: String, accelerationSource: WireSource = WireSource.REAL) {
        if (running) return

        handler.removeCallbacksAndMessages(null)
        acceleration.clear()
        sessionId = confirmedSessionId
        this.accelerationSource = accelerationSource
        discarded = 0L
        running = true
        report("Samples skipped before sending: 0")
        handler.postDelayed(tick, 500L)
    }

    fun add(record: AccelerometerRecord) {
        if (!running) return
        acceleration.add(WireSample(record.sequence, record.timestampNanos,
            x = record.x.toDouble(), y = record.y.toDouble(), z = record.z.toDouble()))
        if (acceleration.size >= CommunicationProtocol.MAX_SAMPLES_PER_BATCH) flush()
    }

    fun add(record: HeartRateRecord) {
        if (!running) return
        transmit(WireDataType.HEART_RATE, WireSource.valueOf(record.source.name),
            listOf(WireSample(record.sequence, record.timestampNanos, bpm = record.bpm)))
    }

    private fun flush() {
        if (acceleration.isEmpty()) return
        val samples = acceleration.toList()
        acceleration.clear()
        transmit(WireDataType.ACCELEROMETER, accelerationSource, samples)
    }

    private fun transmit(type: WireDataType, source: WireSource, samples: List<WireSample>) {
        val peer = nodeId()
        val accepted = peer != null && sender.sendBatch(peer, SensorBatch(
            sessionId, UUID.randomUUID().toString(), type, source, samples))
        if (!accepted) discarded += samples.size
        report("Samples skipped before sending: $discarded")
    }

    fun stop() {
        if (running) flush()
        running = false
        handler.removeCallbacksAndMessages(null)
        acceleration.clear()
    }
}
