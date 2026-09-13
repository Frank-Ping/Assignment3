package com.example.mobile_wearableapplication.communication

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.util.UUID

enum class DeviceRole {
    PHONE,
    WATCH
}

enum class ConnectionStatus {
    STOPPED,
    SEARCHING,
    DISCONNECTED,
    WAITING_FOR_APP,
    CONNECTED,
    ERROR
}

data class ConnectionInfo(
    val status: ConnectionStatus,
    val nodeId: String? = null,
    val detail: String = ""
)

class WearConnectionManager(
    context: Context,
    private val localRole: DeviceRole,
    private val onChanged: (ConnectionInfo) -> Unit
) {
    private val appContext = context.applicationContext
    private val nodeClient = Wearable.getNodeClient(appContext)
    private val messageClient = Wearable.getMessageClient(appContext)
    private val handler = Handler(Looper.getMainLooper())

    private val expectedRole =
        if (localRole == DeviceRole.PHONE) {
            DeviceRole.WATCH
        } else {
            DeviceRole.PHONE
        }

    private var generation = 0
    private var running = false
    private var listener: MessageClient.OnMessageReceivedListener? = null

    private var current = ConnectionInfo(ConnectionStatus.STOPPED)
    private val pendingRequests = mutableMapOf<String, String>()
    private var acknowledgedNode: String? = null

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())

        if (running) return

        running = true
        val token = ++generation

        update(ConnectionInfo(ConnectionStatus.SEARCHING))

        val newListener = MessageClient.OnMessageReceivedListener { event ->
            handler.post {
                if (isCurrent(token)) {
                    handleMessage(event)
                }
            }
        }

        listener = newListener

        messageClient.addListener(newListener)
            .addOnSuccessListener {
                if (isCurrent(token)) {
                    discover(token)
                } else {
                    messageClient.removeListener(newListener)
                }
            }
            .addOnFailureListener { error ->
                if (isCurrent(token)) {
                    update(
                        ConnectionInfo(
                            ConnectionStatus.ERROR,
                            detail = error.message ?: "Listener registration failed"
                        )
                    )
                    stopListeningAfterFailure()
                }
            }
    }

    fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper())

        running = false
        generation++

        handler.removeCallbacksAndMessages(null)

        listener?.let {
            messageClient.removeListener(it)
        }
        listener = null

        pendingRequests.clear()
        acknowledgedNode = null

        update(ConnectionInfo(ConnectionStatus.STOPPED))
    }

    private fun stopListeningAfterFailure() {
        running = false
        generation++
        handler.removeCallbacksAndMessages(null)
        listener?.let { messageClient.removeListener(it) }
        listener = null
    }

    private fun isCurrent(token: Int): Boolean {
        return running && generation == token
    }

    private fun discover(token: Int) {
        if (!isCurrent(token)) return

        pendingRequests.clear()
        acknowledgedNode = null

        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                if (!isCurrent(token)) return@addOnSuccessListener

                if (nodes.isEmpty()) {
                    update(ConnectionInfo(ConnectionStatus.DISCONNECTED))
                    scheduleNext(token)
                    return@addOnSuccessListener
                }

                if (current.status != ConnectionStatus.CONNECTED) {
                    update(ConnectionInfo(ConnectionStatus.WAITING_FOR_APP))
                }

                for (node in nodes) {
                    val requestId = UUID.randomUUID().toString()
                    pendingRequests[node.id] = requestId

                    sendHandshake(
                        nodeId = node.id,
                        path = CommunicationProtocol.HELLO_PATH,
                        requestId = requestId
                    )
                }

                handler.postDelayed({
                    if (isCurrent(token)) {
                        if (acknowledgedNode == null) {
                            update(
                                ConnectionInfo(
                                    ConnectionStatus.WAITING_FOR_APP,
                                    detail = "Device reachable; app has not replied"
                                )
                            )
                        }

                        pendingRequests.clear()
                        scheduleNext(token)
                    }
                }, ACK_TIMEOUT_MS)
            }
            .addOnFailureListener { error ->
                if (isCurrent(token)) {
                    update(
                        ConnectionInfo(
                            ConnectionStatus.ERROR,
                            detail = error.message ?: "Device discovery failed"
                        )
                    )
                    scheduleNext(token)
                }
            }
    }

    private fun scheduleNext(token: Int) {
        handler.postDelayed({
            if (isCurrent(token)) {
                discover(token)
            }
        }, POLL_DELAY_MS)
    }

    private fun handleMessage(event: MessageEvent) {
        if (
            event.path != CommunicationProtocol.HELLO_PATH &&
            event.path != CommunicationProtocol.HELLO_ACK_PATH
        ) {
            return
        }

        val requestId = readHandshake(event.data) ?: return

        when (event.path) {
            CommunicationProtocol.HELLO_PATH -> {
                sendHandshake(
                    nodeId = event.sourceNodeId,
                    path = CommunicationProtocol.HELLO_ACK_PATH,
                    requestId = requestId
                )
            }

            CommunicationProtocol.HELLO_ACK_PATH -> {
                val expectedRequest = pendingRequests[event.sourceNodeId]

                if (expectedRequest != requestId) return

                pendingRequests.remove(event.sourceNodeId)

                // Select one responding peer for this foreground connection.
                if (acknowledgedNode == null) {
                    acknowledgedNode = event.sourceNodeId

                    update(
                        ConnectionInfo(
                            status = ConnectionStatus.CONNECTED,
                            nodeId = event.sourceNodeId,
                            detail = "Handshake acknowledged"
                        )
                    )
                }
            }
        }
    }

    private fun readHandshake(payload: ByteArray): String? {
        if (payload.isEmpty() || payload.size > MAX_HANDSHAKE_BYTES) {
            return null
        }

        return runCatching {
            val json = JSONObject(payload.toString(Charsets.UTF_8))

            val version = json.get("schemaVersion")
            require(
                version is Int &&
                        version == CommunicationProtocol.SCHEMA_VERSION
            )

            val role = json.get("role")
            require(role is String && role == expectedRole.name)

            val requestId = json.get("requestId")
            require(
                requestId is String &&
                        requestId.isNotBlank() &&
                        requestId.length <= 128
            )

            requestId
        }.getOrNull()
    }

    private fun sendHandshake(
        nodeId: String,
        path: String,
        requestId: String
    ) {
        val payload = JSONObject().apply {
            put("schemaVersion", CommunicationProtocol.SCHEMA_VERSION)
            put("role", localRole.name)
            put("requestId", requestId)
        }.toString().toByteArray(Charsets.UTF_8)

        messageClient.sendMessage(nodeId, path, payload)
            .addOnFailureListener { error ->
                Log.w(TAG, "Handshake send failed: $path", error)
            }
    }

    private fun update(info: ConnectionInfo) {
        if (current == info) return

        current = info
        Log.d(TAG, "${localRole.name}: $info")
        onChanged(info)
    }

    companion object {
        private const val TAG = "WearConnection"
        private const val MAX_HANDSHAKE_BYTES = 1024
        private const val ACK_TIMEOUT_MS = 3_000L
        private const val POLL_DELAY_MS = 5_000L
    }
}