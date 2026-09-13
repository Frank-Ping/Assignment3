package com.example.shared.communication

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable

/** Main-thread session transport with independent lifecycle from sensor batches. */
class SessionTransport(
    context: Context, private val peer: () -> String?,
    private val receive: (String, String, ByteArray) -> Unit,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val client = Wearable.getMessageClient(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: MessageClient.OnMessageReceivedListener? = null
    private var generation = 0
    @Volatile private var peerEpoch = 0
    fun peerChanged() { peerEpoch++ }
    var ready = false
        private set
    fun start() {
        if (listener != null) return
        val token = ++generation
        val callback = MessageClient.OnMessageReceivedListener { event ->
            if (event.path in setOf(CommunicationProtocol.SESSION_COMMAND_PATH, CommunicationProtocol.SESSION_STATE_PATH, CommunicationProtocol.SESSION_QUERY_PATH, CommunicationProtocol.SOURCE_COMMAND_PATH)) {
                val connection = peerEpoch
                handler.post {
                    if (token == generation && connection == peerEpoch && ready && event.sourceNodeId == peer()) {
                        runCatching { receive(event.sourceNodeId, event.path, event.data) }
                            .onFailure { onError("Invalid session message: ${it.message}") }
                    }
                }
            }
        }
        listener = callback
        client.addListener(callback).addOnSuccessListener {
            if (token == generation) { ready = true; onReady() } else client.removeListener(callback)
        }.addOnFailureListener {
            if (token == generation) { listener = null; onError("Session listener: ${it.message}") }
        }
    }
    fun send(node: String, path: String, payload: ByteArray) {
        if (!ready || node != peer()) { onError("Session connection unavailable"); return }
        val token = generation
        val connection = peerEpoch
        client.sendMessage(node, path, payload).addOnFailureListener {
            if (token == generation && connection == peerEpoch) onError("Session send failed; outcome unknown: ${it.message}")
        }
    }
    fun stop() {
        generation++; peerEpoch++; ready = false
        listener?.let { client.removeListener(it) }; listener = null
        handler.removeCallbacksAndMessages(null)
    }
}


