package com.example.shared.communication

import org.json.JSONArray
import org.json.JSONObject

enum class SessionAction(val label: String) {
    START_SESSION("Start Session"), START_WORKOUT("Start Workout"),
    END_WORKOUT("End Workout"), FINISH_SESSION("Finish Session")
}
enum class SessionPhase(val label: String) { RESTING("Resting"), EXERCISING("Exercising"), RECOVERING("Recovering") }
enum class SessionLifecycle { IDLE, RUNNING, ENDED, INTERRUPTED }
data class PhaseTransition(val phase: SessionPhase, val revision: Long, val watchElapsedTimeNanos: Long)
data class SessionState(
    val sessionId: String? = null,
    val revision: Long = 0,
    val lifecycle: SessionLifecycle = SessionLifecycle.IDLE,
    val transitions: List<PhaseTransition> = emptyList(),
    val endedAtNanos: Long? = null
) {
    val phase: SessionPhase? get() = transitions.lastOrNull()?.phase
    fun allows(action: SessionAction): Boolean = when (action) {
        SessionAction.START_SESSION -> lifecycle != SessionLifecycle.RUNNING
        SessionAction.START_WORKOUT -> lifecycle == SessionLifecycle.RUNNING && phase == SessionPhase.RESTING
        SessionAction.END_WORKOUT -> lifecycle == SessionLifecycle.RUNNING && phase == SessionPhase.EXERCISING
        SessionAction.FINISH_SESSION -> lifecycle == SessionLifecycle.RUNNING
    }
}
data class SessionCommand(val commandId: String, val action: SessionAction, val expectedSessionId: String?, val expectedRevision: Long)
data class SessionReply(val requestId: String, val accepted: Boolean, val error: String?, val state: SessionState, val heartRateSource: String? = null)

/** Identical wire format in both application modules; longs are decimal strings. */
object SessionProtocol {
    private fun parse(bytes: ByteArray): JSONObject {
        require(bytes.size in 1..8192)
        return JSONObject(bytes.toString(Charsets.UTF_8)).also { require(it.getInt("version") == 1) }
    }
    private fun id(value: String): String = value.also { require(it.isNotBlank() && it.length <= 128) }
    private fun number(json: JSONObject, key: String): Long = json.getString(key).toLong().also { require(it >= 0) }
    private fun optionalId(json: JSONObject, key: String): String? = if (json.isNull(key)) null else id(json.getString(key))
    fun encodeCommand(command: SessionCommand): ByteArray = JSONObject().apply {
        put("version", 1); put("requestId", command.commandId); put("action", command.action.name)
        put("sessionId", command.expectedSessionId ?: JSONObject.NULL); put("revision", command.expectedRevision.toString())
    }.toString().toByteArray(Charsets.UTF_8)
    fun decodeCommand(bytes: ByteArray): SessionCommand = parse(bytes).let {
        SessionCommand(id(it.getString("requestId")), SessionAction.valueOf(it.getString("action")), optionalId(it, "sessionId"), number(it, "revision"))
    }
    fun encodeQuery(requestId: String): ByteArray = JSONObject().apply {
        put("version", 1); put("requestId", requestId)
    }.toString().toByteArray(Charsets.UTF_8)
    fun decodeQuery(bytes: ByteArray): String = id(parse(bytes).getString("requestId"))
    fun encodeReply(reply: SessionReply): ByteArray = JSONObject().apply {
        put("version", 1); put("requestId", reply.requestId); put("accepted", reply.accepted)
        put("heartRateSource", reply.heartRateSource ?: JSONObject.NULL)
        put("error", reply.error ?: JSONObject.NULL)
        put("sessionId", reply.state.sessionId ?: JSONObject.NULL)
        put("revision", reply.state.revision.toString()); put("lifecycle", reply.state.lifecycle.name)
        put("endedAt", reply.state.endedAtNanos?.toString() ?: JSONObject.NULL)
        put("transitions", JSONArray().apply { reply.state.transitions.forEach { transition ->
            put(JSONObject().apply {
                put("phase", transition.phase.name); put("revision", transition.revision.toString())
                put("time", transition.watchElapsedTimeNanos.toString())
            })
        } })
    }.toString().toByteArray(Charsets.UTF_8)
    fun decodeReply(bytes: ByteArray): SessionReply = parse(bytes).let { json ->
        val list = json.getJSONArray("transitions")
        require(list.length() in 0..3)
        val transitions = (0 until list.length()).map { index -> list.getJSONObject(index).let {
            PhaseTransition(SessionPhase.valueOf(it.getString("phase")), number(it, "revision"), number(it, "time"))
        } }
        transitions.forEachIndexed { index, t ->
            require(t.phase == SessionPhase.entries[index] && t.revision == index + 1L)
            if (index > 0) require(t.watchElapsedTimeNanos >= transitions[index - 1].watchElapsedTimeNanos)
        }
        val state = SessionState(optionalId(json, "sessionId"), number(json, "revision"),
            SessionLifecycle.valueOf(json.getString("lifecycle")), transitions,
            if (json.isNull("endedAt")) null else number(json, "endedAt"))
        if (state.lifecycle == SessionLifecycle.IDLE) {
            require(state.sessionId == null && transitions.isEmpty() && state.revision == 0L && state.endedAtNanos == null)
        } else {
            require(state.sessionId != null && transitions.isNotEmpty())
            require(state.revision == transitions.last().revision + if (state.lifecycle == SessionLifecycle.RUNNING) 0L else 1L)
            if (state.lifecycle == SessionLifecycle.RUNNING) require(state.endedAtNanos == null)
            else require(state.endedAtNanos != null && state.endedAtNanos >= transitions.last().watchElapsedTimeNanos)
        }
        SessionReply(id(json.getString("requestId")), json.getBoolean("accepted"),
            if (json.isNull("error")) null else json.getString("error").take(256), state,
            if (json.isNull("heartRateSource")) null else json.getString("heartRateSource").also { require(it == "REAL" || it == "DEMO") })
    }
}

