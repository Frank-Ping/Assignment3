package com.example.mobile_wearableapplication.communication

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

data class SessionControlUi(
    val state: SessionState? = null, val synchronized: Boolean = false,
    val pending: Boolean = false, val message: String = "Waiting for watch"
)
class PhoneSessionClient(context: Context, private val onState: (String, SessionState) -> Unit, private val onUi: (SessionControlUi) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var peer: String? = null
    private var ui = SessionControlUi()
    private var requestId: String? = null
    private val transport = SessionTransport(context, { peer }, { node, path, bytes ->
        if (path == CommunicationProtocol.SESSION_STATE_PATH) {
            val reply = SessionProtocol.decodeReply(bytes)
            if (reply.requestId == requestId) {
                requestId = null
                handler.removeCallbacksAndMessages(null)
                val previous = ui.state
                if (previous?.sessionId == reply.state.sessionId && previous != null && reply.state.revision < previous.revision) {
                    update(ui.copy(pending = false, synchronized = false, message = "Old reply ignored; sync state"))
                } else {
                    onState(node, reply.state)
                    update(SessionControlUi(reply.state, true, false, reply.error ?: "Watch confirmed"))
                }
            }
        }
    }, { sync() }, { message -> update(ui.copy(message = message)) })

    fun start() = transport.start()
    fun setPeer(node: String?) {
        if (peer == node) return
        peer = node; requestId = null; handler.removeCallbacksAndMessages(null)
        update(SessionControlUi(message = if (node == null) "Disconnected; phase changes disabled" else "Synchronizing"))
        if (node != null) sync()
    }
    fun sync() {
        val node = peer ?: return
        if (!transport.ready || ui.pending) return
        val id = UUID.randomUUID().toString()
        beginRequest(id, "Waiting for watch state")
        transport.send(node, CommunicationProtocol.SESSION_QUERY_PATH, SessionProtocol.encodeQuery(id))
    }
    fun command(action: SessionAction) {
        val node = peer ?: return
        val state = ui.state ?: return
        if (!transport.ready || !ui.synchronized || ui.pending || !state.allows(action)) return
        val id = UUID.randomUUID().toString()
        beginRequest(id, "Waiting for confirmation: ${action.label}")
        transport.send(node, CommunicationProtocol.SESSION_COMMAND_PATH,
            SessionProtocol.encodeCommand(SessionCommand(id, action, state.sessionId, state.revision)))
    }
    private fun beginRequest(id: String, message: String) {
        requestId = id
        update(ui.copy(pending = true, synchronized = false, message = message))
        handler.postDelayed({
            if (requestId == id) {
                requestId = null
                update(ui.copy(pending = false, synchronized = false, message = "Confirmation timeout; outcome unknown. Sync state."))
            }
        }, 5_000L)
    }
    fun stop() {
        transport.stop(); handler.removeCallbacksAndMessages(null); requestId = null; peer = null
        update(ui.copy(synchronized = false, pending = false, message = "Session controls stopped"))
    }
    private fun update(value: SessionControlUi) { ui = value; onUi(value) }
}
