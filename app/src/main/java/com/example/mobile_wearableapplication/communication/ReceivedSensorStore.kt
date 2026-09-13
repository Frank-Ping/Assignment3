package com.example.mobile_wearableapplication.communication

import android.os.SystemClock
import java.util.TreeMap
import com.example.mobile_wearableapplication.processing.AccelerationInput
import com.example.mobile_wearableapplication.processing.HeartRateInput
import com.example.mobile_wearableapplication.processing.ProcessingSession
import com.example.mobile_wearableapplication.processing.ProcessingSnapshot
import com.example.mobile_wearableapplication.processing.SampleSource
import com.example.mobile_wearableapplication.processing.SensorProcessingEngine
import com.example.mobile_wearableapplication.processing.ConfirmedPhaseEvent

data class ReceivedSample(
    val sample: WireSample,
    val source: WireSource,
    val receivedAtMillis: Long
)

data class ReceivedStreamSnapshot(
    val latest: ReceivedSample?,
    val history: List<ReceivedSample>,
    val batches: Long,
    val receivedSamples: Long,
    val duplicateBatches: Long,
    val lastNewSampleAtMillis: Long?
)

data class ReceivedSessionSnapshot(
    val nodeId: String,
    val sessionId: String,
    val streams: Map<WireDataType, ReceivedStreamSnapshot>
)

/** Process-local rolling history. No disk persistence or cross-device clock comparison. */
object ReceivedSensorStore {
    const val ACCELERATION_CAPACITY = 1500
    const val HEART_RATE_CAPACITY = 600
    const val STALE_AFTER_MILLIS = 10_000L
    private const val SESSION_CAPACITY = 4
    private const val BATCH_ID_CAPACITY = 256

    private class Stream {
        val samples = TreeMap<Long, ReceivedSample>()
        val batchIds = LinkedHashSet<String>()
        var latest: ReceivedSample? = null
        var batches = 0L
        var receivedSamples = 0L
        var duplicateBatches = 0L
        var lastNewSampleAtMillis: Long? = null
    }

    private val sessions = linkedMapOf<Pair<String, String>, MutableMap<WireDataType, Stream>>()
    private var activeSession: Pair<String, String>? = null
    private val processor = SensorProcessingEngine()

    @Synchronized
    fun accept(nodeId: String, batch: SensorBatch) {
        val key = nodeId to batch.sessionId
        val streams = sessions[key] ?: mutableMapOf<WireDataType, Stream>().also {
            sessions[key] = it
            activeSession = key
            // Temporary session ownership follows the existing store selection.
            // Step 3 will select sessions using watch-confirmed session state.
            processor.startSession(ProcessingSession(nodeId, batch.sessionId))
            if (sessions.size > SESSION_CAPACITY) sessions.remove(sessions.keys.first())
        }
        val stream = streams.getOrPut(batch.dataType) { Stream() }
        if (!stream.batchIds.add(batch.batchId)) {
            stream.duplicateBatches++
            return
        }
        if (stream.batchIds.size > BATCH_ID_CAPACITY) stream.batchIds.remove(stream.batchIds.first())
        stream.batches++
        stream.receivedSamples += batch.samples.size
        val now = SystemClock.elapsedRealtime()
        val newlyAccepted = mutableListOf<WireSample>()
        for (sample in batch.samples) {
            val received = ReceivedSample(sample, batch.source, now)
            if (stream.samples.putIfAbsent(sample.timestampNanos, received) != null) continue
            newlyAccepted.add(sample)
            val latest = stream.latest
            if (latest == null || sample.timestampNanos > latest.sample.timestampNanos) {
                stream.latest = received
                stream.lastNewSampleAtMillis = now
            }
        }
        val capacity = if (batch.dataType == WireDataType.ACCELEROMETER) {
            ACCELERATION_CAPACITY
        } else HEART_RATE_CAPACITY
        while (stream.samples.size > capacity) stream.samples.pollFirstEntry()
        if (key == activeSession) {
            val session = ProcessingSession(nodeId, batch.sessionId)
            val source = SampleSource.valueOf(batch.source.name)
            when (batch.dataType) {
                WireDataType.ACCELEROMETER -> processor.acceptAcceleration(session, newlyAccepted.map {
                    AccelerationInput(it.sequence, it.timestampNanos, source,
                        checkNotNull(it.x), checkNotNull(it.y), checkNotNull(it.z))
                })
                WireDataType.HEART_RATE -> processor.acceptHeartRate(session, newlyAccepted.map {
                    HeartRateInput(it.sequence, it.timestampNanos, source, checkNotNull(it.bpm))
                })
            }
        }
    }

    @Synchronized
    fun processingSnapshot(): ProcessingSnapshot = processor.snapshot()

    @Synchronized
    fun acceptConfirmedPhase(event: ConfirmedPhaseEvent) {
        processor.acceptConfirmedPhase(event)
    }

    @Synchronized
    fun snapshot(): ReceivedSessionSnapshot? {
        val key = activeSession ?: return null
        val streams = sessions.getValue(key).mapValues { (_, stream) ->
            ReceivedStreamSnapshot(stream.latest, stream.samples.values.toList(), stream.batches,
                stream.receivedSamples, stream.duplicateBatches, stream.lastNewSampleAtMillis)
        }
        return ReceivedSessionSnapshot(key.first, key.second, streams)
    }
}
