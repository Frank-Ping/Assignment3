package com.example.wear.presentation.communication

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable

class SensorDataSender(context: Context, private val reportStatus: (String) -> Unit) {
    private val client = Wearable.getMessageClient(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: MessageClient.OnMessageReceivedListener? = null
    private var generation = 0
    private var ready = false
    private val pending = mutableMapOf<Pair<String, String>, String>()

    fun start() {
        if (listener != null) return
        val token = ++generation
        val callback = MessageClient.OnMessageReceivedListener { event ->
            handler.post {
                if (token == generation && event.path == CommunicationProtocol.SENSOR_ACK_PATH) {
                    CommunicationProtocol.decodeAcknowledgement(event.data).onSuccess { ack ->
                        val key = ack.sessionId to ack.batchId
                        if (pending[key] == event.sourceNodeId) {
                            pending.remove(key)
                            report("ACK received: ${ack.batchId}")
                            Log.d("SensorTransfer", "Confirmed session=${ack.sessionId}, batch=${ack.batchId}")
                        }
                    }
                }
            }
        }
        listener = callback
        client.addListener(callback).addOnSuccessListener {
            if (token == generation) {
                ready = true
                report("Sender ready")
            } else client.removeListener(callback)
        }.addOnFailureListener {
            if (token == generation) {
                listener = null
                report("Sender error: ${it.message}")
            }
        }
    }

    fun sendBatch(nodeId: String, batch: SensorBatch) {
        if (!ready) { report("Sender not ready"); return }
        // Keep this manual test queue bounded until retry handling is added.
        if (pending.size >= 10) { report("Too many unconfirmed batches"); return }
        val bytes = runCatching { CommunicationProtocol.encodeBatch(batch) }.getOrElse {
            report("Encoding failed: ${it.message}"); return
        }
        val key = batch.sessionId to batch.batchId
        if (key in pending) return
        val token = generation
        pending[key] = nodeId
        Log.d("SensorTransfer", "Sending $batch")
        report("Sending ${batch.dataType}")
        client.sendMessage(nodeId, CommunicationProtocol.SENSOR_BATCH_PATH, bytes)
            .addOnSuccessListener {
                if (token == generation && key in pending) report("Queued; awaiting ACK")
            }.addOnFailureListener {
                if (token == generation && pending.remove(key) != null) report("Send failed: ${it.message}")
            }
    }

    fun stop() {
        generation++
        ready = false
        handler.removeCallbacksAndMessages(null)
        listener?.let { client.removeListener(it) }
        listener = null
        if (pending.isNotEmpty()) Log.w("SensorTransfer", "Unconfirmed batches: ${pending.size}")
        pending.clear()
        report("Sender stopped")
    }

    private fun report(message: String) {
        Log.d("SensorTransfer", message)
        reportStatus(message)
    }
}
