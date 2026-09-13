package com.example.mobile_wearableapplication.communication

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable

class SensorDataReceiver(
    context: Context,
    private val expectedNodeId: () -> String?,
    private val onBatchReceived: (SensorBatch) -> Unit,
    private val reportStatus: (String) -> Unit
) {
    private val client = Wearable.getMessageClient(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: MessageClient.OnMessageReceivedListener? = null
    private var generation = 0

    fun start() {
        if (listener != null) return
        val token = ++generation
        val callback = MessageClient.OnMessageReceivedListener { event ->
            handler.post {
                if (token == generation && event.path == CommunicationProtocol.SENSOR_BATCH_PATH) {
                    if (event.sourceNodeId != expectedNodeId()) {
                        report("Rejected unconfirmed peer")
                    } else {
                        CommunicationProtocol.decodeBatch(event.data).fold(
                            onSuccess = { batch ->
                                runCatching { onBatchReceived(batch) }.fold(
                                    onSuccess = {
                                        Log.d("SensorTransfer", "Stored ${batch.dataType}: ${batch.samples.size} samples, session=${batch.sessionId}, batch=${batch.batchId}")
                                        val ack = CommunicationProtocol.encodeAcknowledgement(
                                            BatchAcknowledgement(batch.sessionId, batch.batchId)
                                        )
                                        client.sendMessage(event.sourceNodeId, CommunicationProtocol.SENSOR_ACK_PATH, ack)
                                            .addOnSuccessListener {
                                                if (token == generation) report("ACK queued: ${batch.batchId}")
                                            }.addOnFailureListener {
                                                if (token == generation) report("ACK failed: ${it.message}")
                                            }
                                    },
                                    onFailure = { report("Acceptance failed: ${it.message}") }
                                )
                            },
                            onFailure = { report("Rejected payload: ${it.message}") }
                        )
                    }
                }
            }
        }
        listener = callback
        client.addListener(callback).addOnSuccessListener {
            if (token == generation) report("Receiver ready") else client.removeListener(callback)
        }.addOnFailureListener {
            if (token == generation) {
                listener = null
                report("Receiver error: ${it.message}")
            }
        }
    }

    fun stop() {
        generation++
        handler.removeCallbacksAndMessages(null)
        listener?.let { client.removeListener(it) }
        listener = null
        report("Receiver stopped")
    }

    private fun report(message: String) {
        Log.d("SensorTransfer", message)
        reportStatus(message)
    }
}
