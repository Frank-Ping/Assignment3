package com.example.wear.presentation.communication

import com.example.shared.communication.SessionAction
import com.example.shared.communication.SessionPhase
import com.example.shared.communication.SessionLifecycle
import com.example.shared.communication.PhaseTransition
import com.example.shared.communication.SessionState
import com.example.shared.communication.SessionCommand
import com.example.shared.communication.SessionReply

import java.util.UUID

/** Watch owns phase times. No Android dependency; foreground owner supplies clock. */
class WatchSessionController(private val clock: () -> Long, private val newId: () -> String = { UUID.randomUUID().toString() }) {
    var state = SessionState()
        private set
    private val replies = linkedMapOf<Pair<String, String>, Pair<SessionCommand, SessionReply>>()

    /** Opening the watch page starts collection even before a phone connects. */
    fun startForPage() {
        if (state.lifecycle != SessionLifecycle.RUNNING) state = newSession(clock())
    }

    private fun newSession(now: Long) = SessionState(newId(), 1, SessionLifecycle.RUNNING,
        listOf(PhaseTransition(SessionPhase.RESTING, 1, now)))

    fun execute(nodeId: String, command: SessionCommand): SessionReply {
        val key = nodeId to command.commandId
        replies[key]?.let { (original, reply) ->
            return if (original == command) reply else SessionReply(command.commandId, false, "Command ID conflict", state)
        }
        val error = when {
            command.expectedSessionId != state.sessionId || command.expectedRevision != state.revision -> "Session changed; query state"
            !state.allows(command.action) -> "Invalid phase transition"
            else -> null
        }
        if (error == null) {
            val now = clock()
            state = when (command.action) {
                SessionAction.START_SESSION -> newSession(now)
                SessionAction.START_WORKOUT, SessionAction.END_WORKOUT -> {
                    val phase = if (command.action == SessionAction.START_WORKOUT) SessionPhase.EXERCISING else SessionPhase.RECOVERING
                    state.copy(revision = state.revision + 1, transitions = state.transitions + PhaseTransition(phase, state.revision + 1, now))
                }
                SessionAction.FINISH_SESSION -> state.copy(revision = state.revision + 1, lifecycle = SessionLifecycle.ENDED, endedAtNanos = now)
            }
        }
        val reply = SessionReply(command.commandId, error == null, error, state)
        replies[key] = command to reply
        if (replies.size > 128) replies.remove(replies.keys.first())
        return reply
    }

    fun interrupt() {
        if (state.lifecycle == SessionLifecycle.RUNNING) state = state.copy(
            revision = state.revision + 1, lifecycle = SessionLifecycle.INTERRUPTED, endedAtNanos = clock())
    }
}
