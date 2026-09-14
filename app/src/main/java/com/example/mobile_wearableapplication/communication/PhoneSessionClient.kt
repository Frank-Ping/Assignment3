package com.example.mobile_wearableapplication.communication

import com.example.shared.communication.CommunicationProtocol
import com.example.shared.communication.SessionAction
import com.example.shared.communication.SessionLifecycle
import com.example.shared.communication.SessionState
import com.example.shared.communication.SessionCommand
import com.example.shared.communication.SessionProtocol
import com.example.shared.communication.SessionTransport

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

data class SessionControlUi(
    val state: SessionState? = null, val synchronized: Boolean = false,
    val pending: Boolean = false,
    val connected: Boolean = false
)
class PhoneSessionClient(
    context: Context,
    private val onState: (String, SessionState) -> Unit,
    private val onUi: (SessionControlUi) -> Unit,
    private val onUnavailable: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var peer: String? = null
    private var lastStateNode: String? = null
    private var ui = SessionControlUi()
    private var requestId: String? = null
    private var queryAttempts = 0
    private val transport = SessionTransport(context, { peer }, { node, path, bytes ->
        if (running && path == CommunicationProtocol.SESSION_STATE_PATH) {
            val reply = SessionProtocol.decodeReply(bytes)
            val previous = ui.state
            // Unsolicited notifications may only terminate the already known session.
            val interruption = reply.requestId == "session-interrupted" &&
                node == lastStateNode && previous?.sessionId == reply.state.sessionId &&
                previous != null && reply.state.revision > previous.revision &&
                reply.state.lifecycle == SessionLifecycle.INTERRUPTED
            if (reply.requestId == requestId || interruption) {
                cancelRequest()
                if (previous != null && previous.sessionId == reply.state.sessionId && reply.state.revision < previous.revision) {
                    onUnavailable("Stale session reply; waiting for confirmation")
                    update(ui.copy(pending = false, synchronized = false))
                } else {
                    lastStateNode = node
                    onState(node, reply.state)
                    update(SessionControlUi(reply.state, true, false, true))
                    scheduleRefresh()
                }
            }
        }
    }, { if (running) sync() }, { message ->
        if (running) {
            onUnavailable(message)
            update(ui.copy(synchronized = false))
        }
    })

    fun start() {
        if (running) return
        running = true
        onUnavailable("Phone page opened; waiting for watch confirmation")
        transport.start()
    }
    fun setPeer(node: String?, reason: String = "Connection unavailable") {
        if (!running || peer == node) return
        peer = node
        transport.peerChanged()
        cancelRequest()
        onUnavailable(if (node == null) reason else "Reconnected; querying watch session")
        val retained = if (lastStateNode == node || node == null) ui.state else null
        update(SessionControlUi(state = retained, connected = node != null))
        if (node != null) sync()
    }
    fun sync() {
        if (!running || peer == null || ui.pending) return
        if (!transport.ready) {
            transport.start()
            return
        }
        queryAttempts = 0
        query()
    }
    private fun query(backgroundRefresh: Boolean = false) {
        val node = peer ?: return
        if (!running || !transport.ready) return
        queryAttempts++
        if (!backgroundRefresh) onUnavailable("Waiting for watch session confirmation")
        val id = UUID.randomUUID().toString()
        beginRequest(id, isQuery = true, retainConfirmation = backgroundRefresh)
        transport.send(node, CommunicationProtocol.SESSION_QUERY_PATH, SessionProtocol.encodeQuery(id))
    }
    fun command(action: SessionAction) {
        val node = peer ?: return
        val state = ui.state ?: return
        if (!running || !transport.ready || !ui.synchronized || ui.pending || !state.allows(action)) return
        val id = UUID.randomUUID().toString()
        beginRequest(id, isQuery = false)
        transport.send(node, CommunicationProtocol.SESSION_COMMAND_PATH,
            SessionProtocol.encodeCommand(SessionCommand(id, action, state.sessionId, state.revision)))
    }
    private fun beginRequest(id: String, isQuery: Boolean, retainConfirmation: Boolean = false) {
        requestId = id
        update(ui.copy(pending = true, synchronized = retainConfirmation && ui.synchronized))
        handler.postDelayed({
            if (running && requestId == id) {
                requestId = null
                onUnavailable("Confirmation timeout; session state unknown")
                if (isQuery && queryAttempts < 3 && peer != null) query()
                else update(ui.copy(pending = false, synchronized = false))
            }
        }, 5_000L)
    }
    private fun cancelRequest() {
        requestId = null
        handler.removeCallbacksAndMessages(null)
    }
    private fun scheduleRefresh() {
        // A quick watch page restart may happen between connection-manager polls.
        // Refresh state even if the reachable node ID never changes.
        handler.postDelayed({
            if (running && peer != null && !ui.pending) {
                queryAttempts = 0
                query(backgroundRefresh = ui.synchronized)
            }
        }, 8_000L)
    }
    fun stop() {
        if (!running) return
        running = false
        onUnavailable("Phone page closed; receiving paused")
        cancelRequest(); transport.stop(); peer = null
        update(ui.copy(synchronized = false, pending = false, connected = false))
    }
    private fun update(value: SessionControlUi) { ui = value; onUi(value) }
}
