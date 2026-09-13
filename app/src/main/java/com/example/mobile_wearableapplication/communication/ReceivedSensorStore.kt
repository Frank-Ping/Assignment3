package com.example.mobile_wearableapplication.communication

import android.os.SystemClock
import java.util.TreeMap

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

    @Synchronized
    fun accept(nodeId: String, batch: SensorBatch) {
        val key = nodeId to batch.sessionId
        val streams = sessions[key] ?: mutableMapOf<WireDataType, Stream>().also {
            sessions[key] = it
            activeSession = key
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
        for (sample in batch.samples) {
            val received = ReceivedSample(sample, batch.source, now)
            stream.samples.putIfAbsent(sample.timestampNanos, received)
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
