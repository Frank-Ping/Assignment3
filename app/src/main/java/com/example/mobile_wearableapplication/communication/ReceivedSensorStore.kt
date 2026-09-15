package com.example.mobile_wearableapplication.communication

import com.example.shared.communication.WireDataType
import com.example.shared.communication.WireSample
import com.example.shared.communication.SensorBatch
import com.example.shared.communication.SessionLifecycle
import com.example.shared.communication.SessionState

import android.os.SystemClock
import java.util.TreeMap
import com.example.mobile_wearableapplication.processing.AccelerationInput
import com.example.mobile_wearableapplication.processing.HeartRateInput
import com.example.mobile_wearableapplication.processing.ProcessingSession
import com.example.mobile_wearableapplication.processing.ProcessingSnapshot
import com.example.mobile_wearableapplication.processing.SampleSource
import com.example.mobile_wearableapplication.processing.SensorProcessingEngine
import com.example.mobile_wearableapplication.processing.ConfirmedPhaseEvent
import com.example.mobile_wearableapplication.processing.WorkoutPhase

data class ReceivedSample(
    val sample: WireSample,
    val receivedAtMillis: Long
)

data class ReceivedStreamSnapshot(
    val latest: ReceivedSample?,
    val lastNewSampleAtMillis: Long?,
    val freshSinceResume: Boolean
)

data class ReceptionDiagnostics(
    val ready: Boolean, val message: String,
    val sessionLifecycle: SessionLifecycle?
)

data class ReceivedSessionSnapshot(
    val streams: Map<WireDataType, ReceivedStreamSnapshot>
)

/** Process-local rolling history. No disk persistence or cross-device clock comparison. */
object ReceivedSensorStore {
    const val ACCELERATION_CAPACITY = 1500
    const val HEART_RATE_CAPACITY = 600
    private const val SESSION_CAPACITY = 4
    private const val BATCH_ID_CAPACITY = 256

    private class Stream {
        val samples = TreeMap<Long, ReceivedSample>()
        val batchIds = LinkedHashSet<String>()
        var latest: ReceivedSample? = null
        var lastNewSampleAtMillis: Long? = null
        var freshSinceResume = false
    }

    private val sessions = linkedMapOf<Pair<String, String>, MutableMap<WireDataType, Stream>>()
    private var activeSession: Pair<String, String>? = null
    private val processor = SensorProcessingEngine()
    private var confirmedState: SessionState? = null
    private var receptionReady = false
    private var receptionMessage = "Waiting for session confirmation"
    private var accelerationTimedOut = false
    private var heartRateTimedOut = false

    @Synchronized
    fun suspendReception(reason: String) {
        if (receptionReady) processor.breakContinuity()
        receptionReady = false
        receptionMessage = reason
        activeSession?.let { sessions[it] }?.values?.forEach { it.freshSinceResume = false }
    }

    @Synchronized
    fun receptionDiagnostics() = ReceptionDiagnostics(receptionReady, receptionMessage,
        confirmedState?.lifecycle)

    @Synchronized
    fun confirmSession(nodeId: String, state: SessionState) {
        val id = state.sessionId
        if (id == null) {
            activeSession = null
            confirmedState = state
            receptionReady = false
            receptionMessage = "Watch has no active session; previous session is not resumed"
            processor.reset()
            return
        }
        val key = nodeId to id
        val previous = confirmedState
        if (activeSession == key && previous != null && state.revision < previous.revision) return
        activeSession = key
        confirmedState = state
        receptionReady = true
        receptionMessage = when (state.lifecycle) {
            SessionLifecycle.RUNNING -> "Session confirmed; waiting for fresh samples after any interruption"
            SessionLifecycle.INTERRUPTED -> "Watch collection interrupted; no automatic restart"
            else -> "Session ended; displaying history"
        }
        sessions.getOrPut(key) { mutableMapOf() }
        while (sessions.size > SESSION_CAPACITY) sessions.remove(sessions.keys.first { it != key })
        val session = ProcessingSession(nodeId, id)
        processor.startSession(session)
        state.transitions.forEach {
            processor.acceptConfirmedPhase(ConfirmedPhaseEvent(session, it.revision,
                WorkoutPhase.valueOf(it.phase.name), it.watchElapsedTimeNanos))
        }
        state.endedAtNanos?.let { processor.endSession(session, it) }
    }

    @Synchronized
    fun accept(nodeId: String, batch: SensorBatch) {
        val key = nodeId to batch.sessionId
        // A sensor batch cannot select or restart a session. No pre-confirmation replay yet.
        if (!receptionReady || key != activeSession) {
            error("Receiving suspended or batch belongs to an unconfirmed session; no replay")
        }
        val state = checkNotNull(confirmedState)
        val streams = sessions.getValue(key)
        val stream = streams.getOrPut(batch.dataType) { Stream() }
        if (!stream.batchIds.add(batch.batchId)) return
        if (stream.batchIds.size > BATCH_ID_CAPACITY) stream.batchIds.remove(stream.batchIds.first())
        val now = SystemClock.elapsedRealtime()
        val newlyAccepted = mutableListOf<WireSample>()
        for (sample in batch.samples) {
            if (sample.timestampNanos < state.transitions.first().watchElapsedTimeNanos ||
                state.endedAtNanos?.let { sample.timestampNanos >= it } == true) continue
            val received = ReceivedSample(sample, now)
            if (stream.samples.putIfAbsent(sample.timestampNanos, received) != null) continue
            newlyAccepted.add(sample)
            val latest = stream.latest
            if (latest == null || sample.timestampNanos > latest.sample.timestampNanos) {
                stream.latest = received
                stream.lastNewSampleAtMillis = now
                stream.freshSinceResume = true
                if (batch.dataType == WireDataType.ACCELEROMETER) accelerationTimedOut = false else heartRateTimedOut = false
            }
        }
        val capacity = if (batch.dataType == WireDataType.ACCELEROMETER) {
            ACCELERATION_CAPACITY
        } else HEART_RATE_CAPACITY
        while (stream.samples.size > capacity) stream.samples.pollFirstEntry()
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

    @Synchronized
    fun processingSnapshot(): ProcessingSnapshot = processor.snapshot()

    @Synchronized
    fun checkReceptionTimeouts() {
        val lastReceipt = activeSession?.let { sessions[it] }?.get(WireDataType.ACCELEROMETER)?.lastNewSampleAtMillis
        // Receipt watchdog allows for the existing 500 ms batches; never subtract watch time here.
        val accelerationExpired = !receptionReady || lastReceipt == null || SystemClock.elapsedRealtime() - lastReceipt > 1_000L
        if (accelerationExpired && !accelerationTimedOut) processor.markAccelerationUnavailable()
        accelerationTimedOut = accelerationExpired
        val hrStream = activeSession?.let { sessions[it] }?.get(WireDataType.HEART_RATE)
        val hrReceipt = hrStream?.lastNewSampleAtMillis
        val heartRateExpired = !receptionReady || (hrReceipt != null && SystemClock.elapsedRealtime() - hrReceipt > 3_000L)
        if (heartRateExpired && !heartRateTimedOut) processor.markHeartRateUnavailable()
        heartRateTimedOut = heartRateExpired
    }

    @Synchronized
    fun snapshot(): ReceivedSessionSnapshot? {
        val key = activeSession ?: return null
        val streams = sessions.getValue(key).mapValues { (_, stream) ->
            ReceivedStreamSnapshot(stream.latest, stream.lastNewSampleAtMillis, stream.freshSinceResume)
        }
        return ReceivedSessionSnapshot(streams)
    }
}
