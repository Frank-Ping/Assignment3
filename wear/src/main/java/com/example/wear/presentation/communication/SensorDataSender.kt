package com.example.wear.presentation.communication

import com.example.shared.communication.SensorBatch
import com.example.shared.communication.CommunicationProtocol

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable

class SensorDataSender(context: Context) {
    private val client = Wearable.getMessageClient(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: MessageClient.OnMessageReceivedListener? = null
    private var generation = 0
    private var ready = false
    private var encoder = Executors.newSingleThreadExecutor()
    private val pending = mutableMapOf<Pair<String, String>, String>()

    fun start() {
        if (listener != null) return
        if (encoder.isShutdown) encoder = Executors.newSingleThreadExecutor()
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

    // Called on the main thread; only JSON encoding runs on the worker.
    fun sendBatch(nodeId: String, batch: SensorBatch) {
        if (!ready || pending.size >= 10) return
        val key = batch.sessionId to batch.batchId
        if (key in pending) return
        val token = generation
        pending[key] = nodeId
        encoder.execute {
            val encoded = runCatching { CommunicationProtocol.encodeBatch(batch) }
            handler.post {
                if (token != generation || key !in pending) return@post
                encoded.fold(onSuccess = { bytes ->
                    Log.d("SensorTransfer", "Sending ${batch.dataType}: ${batch.samples.size} samples, batch=${batch.batchId}")
                    val timeout = Runnable {
                        if (token == generation && pending.remove(key) != null) {
                            report("ACK timeout; delivery unknown")
                        }
                    }
                    handler.postDelayed(timeout, 5_000L)
                    client.sendMessage(nodeId, CommunicationProtocol.SENSOR_BATCH_PATH, bytes)
                        .addOnSuccessListener {
                            if (token == generation && key in pending) report("Queued; awaiting ACK")
                        }.addOnFailureListener {
                            handler.removeCallbacks(timeout)
                            if (token == generation && pending.remove(key) != null) {
                                report("Send failed: ${it.message}")
                            }
                        }
                }, onFailure = {
                    pending.remove(key)
                    report("Encoding failed: ${it.message}")
                })
            }
        }
    }

    fun stop() {
        generation++
        ready = false
        encoder.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        listener?.let { client.removeListener(it) }
        listener = null
        if (pending.isNotEmpty()) Log.w("SensorTransfer", "Unconfirmed batches: ${pending.size}")
        pending.clear()
        report("Sender stopped")
    }

    private fun report(message: String) {
        Log.d("SensorTransfer", message)
    }
}
